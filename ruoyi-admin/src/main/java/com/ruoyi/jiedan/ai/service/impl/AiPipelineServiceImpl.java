package com.ruoyi.jiedan.ai.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.jiedan.ai.domain.AiAttempt;
import com.ruoyi.jiedan.ai.domain.AiProject;
import com.ruoyi.jiedan.ai.domain.AiTask;
import com.ruoyi.jiedan.ai.mapper.AiAttemptMapper;
import com.ruoyi.jiedan.ai.mapper.AiEventMapper;
import com.ruoyi.jiedan.ai.mapper.AiProjectMapper;
import com.ruoyi.jiedan.ai.mapper.AiTaskMapper;
import com.ruoyi.jiedan.ai.service.IAiPipelineService;

/**
 * AI 改码控制面状态机。
 * 所有 Worker 写入都由 attemptId + fenceToken + workerId 三元组围栏，避免过期 Worker 覆盖新结果。
 */
@Service
public class AiPipelineServiceImpl implements IAiPipelineService
{
    private static final long LEASE_MILLIS = 120_000L;
    private static final String DEFAULT_FORBIDDEN = "[\".github/workflows/**\",\"**/.env*\",\"**/*secret*\",\"deploy/**\",\"sql/**\"]";

    @Autowired private AiProjectMapper projectMapper;
    @Autowired private AiTaskMapper taskMapper;
    @Autowired private AiAttemptMapper attemptMapper;
    @Autowired private AiEventMapper eventMapper;

    @Override
    public List<Map<String, Object>> listProjects(String keyword, Integer enabled)
    {
        return projectMapper.selectList(trim(keyword), enabled);
    }

    @Override
    public Map<String, Object> getProject(Long id)
    {
        AiProject p = requiredProject(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", p.getId());
        out.put("name", p.getName());
        out.put("repoUrl", p.getRepoUrl());
        out.put("defaultBranch", p.getDefaultBranch());
        out.put("provider", p.getProvider());
        out.put("model", p.getModel());
        out.put("profileKey", p.getProfileKey());
        out.put("validationCommands", parseJsonOrString(p.getValidationCommands()));
        out.put("forbiddenPaths", parseJsonOrString(p.getForbiddenPaths()));
        out.put("maxParallel", p.getMaxParallel());
        out.put("enabled", p.getEnabled());
        out.put("version", p.getVersion());
        out.put("updateTime", p.getUpdateTime());
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> createProject(Map<String, Object> body, String actor)
    {
        AiProject p = projectFrom(body, null, actor);
        if (projectMapper.insert(p) != 1) throw new ServiceException("创建 AI 项目失败");
        return getProject(p.getId());
    }

    @Override
    @Transactional
    public Map<String, Object> updateProject(Map<String, Object> body, String actor)
    {
        Long id = longValue(body.get("id"));
        if (id == null) throw new ServiceException("项目 id 不能为空");
        AiProject old = requiredProject(id);
        AiProject p = projectFrom(body, old, actor);
        p.setId(id);
        p.setVersion(integer(body.get("version"), old.getVersion()));
        if (projectMapper.update(p) != 1) throw new ServiceException("项目已被其他人修改，请刷新后重试");
        return getProject(id);
    }

    @Override
    public List<Map<String, Object>> listTasks(Long projectId, String status, String keyword)
    {
        return taskMapper.selectList(projectId, trim(status), trim(keyword));
    }

    @Override
    public Map<String, Object> getTask(Long id)
    {
        Map<String, Object> out = taskMapper.selectDetail(id);
        if (out == null) throw new ServiceException("AI 任务不存在");
        out.put("attempts", attemptMapper.selectByTaskId(id));
        out.put("events", eventMapper.selectByTaskId(id));
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> createTask(Map<String, Object> body, String actor)
    {
        Long projectId = longValue(body.get("projectId"));
        AiProject project = requiredProject(projectId);
        if (!Integer.valueOf(1).equals(project.getEnabled())) throw new ServiceException("项目已停用，不能创建任务");
        String title = requiredText(body.get("title"), "任务标题", 120);
        String prompt = requiredText(body.get("prompt"), "改码说明", 30_000);
        if (prompt.length() < 20) throw new ServiceException("改码说明至少需要 20 个字符");
        String riskLevel = enumValue(body.get("riskLevel"), "low", "low", "medium", "high");
        Date now = new Date();
        AiTask task = new AiTask();
        task.setProjectId(projectId);
        task.setOrderId(longValue(body.get("orderId")));
        task.setBugId(longValue(body.get("bugId")));
        task.setTitle(title);
        task.setPrompt(prompt.trim());
        task.setPromptVersion(1);
        task.setPromptHash(sha256("1\n" + normalizePrompt(prompt)));
        task.setRiskLevel(riskLevel);
        task.setStatus("draft");
        task.setCreatedBy(actor);
        task.setUpdatedBy(actor);
        task.setCreateTime(now);
        task.setUpdateTime(now);
        if (taskMapper.insert(task) != 1) throw new ServiceException("创建 AI 任务失败");
        event(task.getId(), null, "task.created", "user", actor, mapOf("promptHash", task.getPromptHash()));
        if (Boolean.TRUE.equals(bool(body.get("dispatch")))) queue(task.getId(), actor, "task.dispatched");
        return getTask(task.getId());
    }

    @Override
    @Transactional
    public Map<String, Object> dispatch(Long id, String actor)
    {
        queue(id, actor, "task.dispatched");
        return getTask(id);
    }

    @Override
    @Transactional
    public Map<String, Object> retry(Long id, String actor)
    {
        queue(id, actor, "task.retried");
        return getTask(id);
    }

    @Override
    @Transactional
    public Map<String, Object> approve(Long id, Map<String, Object> body, String actor)
    {
        AiTask task = requiredTask(id);
        if (!"awaiting_review".equals(task.getStatus()) || task.getCurrentAttemptId() == null)
            throw new ServiceException("任务不在待审核状态");
        AiAttempt attempt = requiredAttempt(task.getCurrentAttemptId());
        String headSha = requiredText(body.get("headSha"), "headSha", 64);
        String diffSha = requiredText(body.get("diffSha"), "diffSha", 64);
        if (!headSha.equals(attempt.getHeadSha()) || !diffSha.equals(attempt.getDiffSha()))
            throw new ServiceException("PR 代码指纹已变化，请刷新并重新审核");
        if (taskMapper.approve(id, attempt.getId(), headSha, diffSha, actor) != 1)
            throw new ServiceException("任务状态已变化，请刷新后重试");
        event(id, attempt.getId(), "review.approved", "user", actor, mapOf("headSha", headSha, "diffSha", diffSha));
        return getTask(id);
    }

    @Override
    @Transactional
    public Map<String, Object> reject(Long id, Map<String, Object> body, String actor)
    {
        AiTask task = requiredTask(id);
        if (task.getCurrentAttemptId() == null || taskMapper.reject(id, task.getCurrentAttemptId(), actor) != 1)
            throw new ServiceException("任务不在待审核状态");
        event(id, task.getCurrentAttemptId(), "review.rejected", "user", actor,
            mapOf("reason", text(body.get("reason"), "未填写原因", 1000)));
        return getTask(id);
    }

    @Override
    @Transactional
    public Map<String, Object> cancel(Long id, String actor)
    {
        requiredTask(id);
        if (taskMapper.cancel(id, actor) != 1) throw new ServiceException("当前状态不能取消");
        event(id, null, "task.cancelled", "user", actor, null);
        return getTask(id);
    }

    @Override
    @Transactional
    public List<Map<String, Object>> candidates(Integer limit)
    {
        recoverExpired();
        int safeLimit = Math.max(1, Math.min(integer(limit, 5), 20));
        return taskMapper.selectCandidates(safeLimit);
    }

    @Override
    @Transactional
    public Map<String, Object> claim(Long taskId, Map<String, Object> body)
    {
        String requestId = requiredText(body.get("requestId"), "requestId", 64);
        String workerId = requiredText(body.get("workerId"), "workerId", 80);
        String baseSha = shaValue(body.get("baseSha"), "baseSha");
        Integer taskVersion = integer(body.get("taskVersion"), null);
        if (taskVersion == null) throw new ServiceException("taskVersion 不能为空");

        AiAttempt duplicate = attemptMapper.selectByRequestId(requestId);
        if (duplicate != null)
        {
            if (!taskId.equals(duplicate.getTaskId()) || !workerId.equals(duplicate.getWorkerId()))
                throw new ServiceException("requestId 已被其他请求使用");
            AiTask duplicateTask = requiredTask(taskId);
            if (!isActive(duplicateTask, duplicate)) throw new ServiceException("requestId 对应的执行凭证已失效");
            return claimResponse(duplicateTask, requiredProject(duplicateTask.getProjectId()), duplicate);
        }

        AiTask task = requiredTask(taskId);
        AiProject project = projectMapper.selectByIdForUpdate(task.getProjectId());
        if (project == null) throw new ServiceException("AI 项目不存在");
        if (!Integer.valueOf(1).equals(project.getEnabled())) throw new ServiceException("项目已停用");
        if (attemptMapper.countActiveByProject(project.getId()) >= project.getMaxParallel())
            throw new ServiceException("项目并发已满，请等待当前任务完成");
        if (taskMapper.claim(taskId, taskVersion, "worker:" + workerId) != 1)
            throw new ServiceException("任务已被其他 Worker 认领或版本已变化");

        int attemptNo = attemptMapper.selectNextAttemptNo(taskId);
        Date now = new Date();
        AiAttempt attempt = new AiAttempt();
        attempt.setTaskId(taskId);
        attempt.setAttemptNo(attemptNo);
        attempt.setRequestId(requestId);
        attempt.setFenceToken(UUID.randomUUID().toString());
        attempt.setWorkerId(workerId);
        attempt.setProvider(project.getProvider());
        attempt.setModel(project.getModel());
        attempt.setStatus("claimed");
        attempt.setLeaseExpireTime(new Date(now.getTime() + LEASE_MILLIS));
        attempt.setBranchName("ai/task-" + taskId + "/attempt-" + attemptNo);
        attempt.setBaseSha(baseSha);
        attempt.setCreateTime(now);
        attempt.setUpdateTime(now);
        if (attemptMapper.insert(attempt) != 1 || taskMapper.bindAttempt(taskId, attempt.getId()) != 1)
            throw new ServiceException("认领任务失败");
        event(taskId, attempt.getId(), "attempt.claimed", "worker", workerId,
            mapOf("baseSha", baseSha, "requestId", requestId));
        task = requiredTask(taskId);
        return claimResponse(task, project, attempt);
    }

    @Override
    @Transactional
    public Map<String, Object> heartbeat(Long attemptId, Map<String, Object> body)
    {
        String workerId = requiredText(body.get("workerId"), "workerId", 80);
        String fenceToken = requiredText(body.get("fenceToken"), "fenceToken", 64);
        AiAttempt attempt = requiredAttempt(attemptId);
        Date lease = new Date(System.currentTimeMillis() + LEASE_MILLIS);
        if (attemptMapper.heartbeat(attemptId, fenceToken, workerId, lease) != 1)
            throw new ServiceException("租约已失效，Worker 必须立即停止当前任务");
        taskMapper.markRunning(attempt.getTaskId(), attemptId);
        return mapOf("attemptId", attemptId, "leaseExpireTime", lease, "leaseSeconds", 120);
    }

    @Override
    @Transactional
    public Map<String, Object> appendLog(Long attemptId, Map<String, Object> body)
    {
        AiAttempt attempt = activeAttempt(attemptId, body);
        Long seq = longValue(body.get("seq"));
        if (seq == null || seq < 1) throw new ServiceException("日志 seq 必须从 1 开始递增");
        String level = enumValue(body.get("level"), "info", "debug", "info", "warn", "error");
        String message = requiredText(body.get("message"), "日志内容", 4000);
        attemptMapper.insertLog(attemptId, seq, level, message, new Date());
        return mapOf("attemptId", attempt.getId(), "seq", seq);
    }

    @Override
    @Transactional
    public Map<String, Object> finish(Long attemptId, Map<String, Object> body)
    {
        AiAttempt current = activeAttempt(attemptId, body);
        String outcome = enumValue(body.get("outcome"), "failed", "succeeded", "failed");
        Date now = new Date();
        AiAttempt update = new AiAttempt();
        update.setId(attemptId);
        update.setFenceToken(current.getFenceToken());
        update.setWorkerId(current.getWorkerId());
        update.setFinishedAt(now);
        update.setUpdateTime(now);

        boolean policyPassed = Boolean.TRUE.equals(bool(body.get("policyPassed")));
        boolean validationPassed = Boolean.TRUE.equals(bool(body.get("validationPassed")));
        update.setPolicyPassed(policyPassed ? 1 : 0);
        update.setValidationPassed(validationPassed ? 1 : 0);
        update.setValidationSummary(text(body.get("validationSummary"), null, 8000));

        if ("succeeded".equals(outcome))
        {
            if (!policyPassed || !validationPassed)
                throw new ServiceException("策略检查和验证必须全部通过，任务才能进入审核");
            update.setHeadSha(shaValue(body.get("headSha"), "headSha"));
            update.setTreeSha(shaValue(body.get("treeSha"), "treeSha"));
            update.setDiffSha(shaValue(body.get("diffSha"), "diffSha"));
            update.setPrUrl(requiredGithubPr(body.get("prUrl")));
            update.setPrNumber(longValue(body.get("prNumber")));
            if (attemptMapper.completeSuccess(update) != 1 || taskMapper.markAwaitingReview(current.getTaskId(), attemptId) != 1)
                throw new ServiceException("租约或任务状态已变化，拒绝写入结果");
            event(current.getTaskId(), attemptId, "attempt.succeeded", "worker", current.getWorkerId(),
                mapOf("headSha", update.getHeadSha(), "diffSha", update.getDiffSha(), "prUrl", update.getPrUrl()));
        }
        else
        {
            update.setErrorCode(text(body.get("errorCode"), "EXECUTION_FAILED", 80));
            update.setErrorMessage(text(body.get("errorMessage"), "Worker 执行失败", 4000));
            if (attemptMapper.completeFailure(update) != 1 || taskMapper.markFailed(current.getTaskId(), attemptId) != 1)
                throw new ServiceException("租约或任务状态已变化，拒绝写入结果");
            event(current.getTaskId(), attemptId, "attempt.failed", "worker", current.getWorkerId(),
                mapOf("errorCode", update.getErrorCode(), "errorMessage", update.getErrorMessage()));
        }
        return getTask(current.getTaskId());
    }

    @Override
    public List<Map<String, Object>> logs(Long attemptId, Long afterSeq, Integer limit)
    {
        requiredAttempt(attemptId);
        long seq = afterSeq == null ? 0L : Math.max(0L, afterSeq);
        int safeLimit = Math.max(1, Math.min(integer(limit, 200), 500));
        return attemptMapper.selectLogs(attemptId, seq, safeLimit);
    }

    private void queue(Long id, String actor, String eventType)
    {
        requiredTask(id);
        if (taskMapper.queue(id, actor) != 1) throw new ServiceException("当前状态不能进入队列");
        event(id, null, eventType, "user", actor, null);
    }

    private void recoverExpired()
    {
        Date now = new Date();
        for (AiAttempt attempt : attemptMapper.selectExpired(now))
        {
            if (attemptMapper.expire(attempt.getId(), attempt.getFenceToken(), now) == 1)
            {
                taskMapper.requeueExpired(attempt.getTaskId(), attempt.getId());
                event(attempt.getTaskId(), attempt.getId(), "attempt.expired", "system", "lease-reaper", null);
            }
        }
    }

    private Map<String, Object> claimResponse(AiTask task, AiProject project, AiAttempt attempt)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskId", task.getId());
        out.put("attemptId", attempt.getId());
        out.put("attemptNo", attempt.getAttemptNo());
        out.put("fenceToken", attempt.getFenceToken());
        out.put("leaseExpireTime", attempt.getLeaseExpireTime());
        out.put("leaseSeconds", 120);
        out.put("branchName", attempt.getBranchName());
        out.put("baseSha", attempt.getBaseSha());
        out.put("title", task.getTitle());
        out.put("prompt", task.getPrompt());
        out.put("promptHash", task.getPromptHash());
        out.put("riskLevel", task.getRiskLevel());
        out.put("projectId", project.getId());
        out.put("projectName", project.getName());
        out.put("repoUrl", project.getRepoUrl());
        out.put("defaultBranch", project.getDefaultBranch());
        out.put("provider", project.getProvider());
        out.put("model", project.getModel());
        out.put("profileKey", project.getProfileKey());
        out.put("validationCommands", parseJsonOrString(project.getValidationCommands()));
        out.put("forbiddenPaths", parseJsonOrString(project.getForbiddenPaths()));
        return out;
    }

    private AiProject projectFrom(Map<String, Object> body, AiProject old, String actor)
    {
        AiProject p = new AiProject();
        p.setName(requiredText(value(body, old, "name", old == null ? null : old.getName()), "项目名称", 80));
        p.setRepoUrl(validateRepo(requiredText(value(body, old, "repoUrl", old == null ? null : old.getRepoUrl()), "仓库地址", 500)));
        p.setDefaultBranch(text(value(body, old, "defaultBranch", old == null ? "main" : old.getDefaultBranch()), "main", 100));
        p.setProvider(enumValue(value(body, old, "provider", old == null ? "codex" : old.getProvider()), "codex", "codex", "claude", "hermes", "custom"));
        p.setModel(text(value(body, old, "model", old == null ? "" : old.getModel()), "", 100));
        p.setProfileKey(text(value(body, old, "profileKey", old == null ? "default" : old.getProfileKey()), "default", 100));
        p.setValidationCommands(jsonArrayValue(value(body, old, "validationCommands", old == null ? "[]" : old.getValidationCommands()), "[]", false));
        p.setForbiddenPaths(jsonArrayValue(value(body, old, "forbiddenPaths", old == null ? DEFAULT_FORBIDDEN : old.getForbiddenPaths()), DEFAULT_FORBIDDEN, true));
        int maxParallel = integer(value(body, old, "maxParallel", old == null ? 1 : old.getMaxParallel()), 1);
        if (maxParallel < 1 || maxParallel > 4) throw new ServiceException("单项目并发数必须在 1 到 4 之间");
        p.setMaxParallel(maxParallel);
        p.setEnabled(Boolean.FALSE.equals(bool(value(body, old, "enabled", old == null ? 1 : old.getEnabled()))) ? 0 : 1);
        p.setCreatedBy(old == null ? actor : old.getCreatedBy());
        p.setCreateTime(old == null ? new Date() : old.getCreateTime());
        p.setUpdatedBy(actor);
        p.setUpdateTime(new Date());
        return p;
    }

    private AiAttempt activeAttempt(Long attemptId, Map<String, Object> body)
    {
        AiAttempt attempt = requiredAttempt(attemptId);
        String workerId = requiredText(body.get("workerId"), "workerId", 80);
        String fenceToken = requiredText(body.get("fenceToken"), "fenceToken", 64);
        if (!workerId.equals(attempt.getWorkerId()) || !fenceToken.equals(attempt.getFenceToken())
            || !("claimed".equals(attempt.getStatus()) || "running".equals(attempt.getStatus())))
            throw new ServiceException("执行凭证已失效");
        AiTask task = requiredTask(attempt.getTaskId());
        if (!isActive(task, attempt)) throw new ServiceException("租约或任务状态已失效");
        return attempt;
    }

    private boolean isActive(AiTask task, AiAttempt attempt)
    {
        return task.getCurrentAttemptId() != null && task.getCurrentAttemptId().equals(attempt.getId())
            && ("claimed".equals(task.getStatus()) || "running".equals(task.getStatus()))
            && ("claimed".equals(attempt.getStatus()) || "running".equals(attempt.getStatus()))
            && attempt.getLeaseExpireTime() != null && !attempt.getLeaseExpireTime().before(new Date());
    }

    private AiProject requiredProject(Long id)
    {
        if (id == null) throw new ServiceException("projectId 不能为空");
        AiProject p = projectMapper.selectById(id);
        if (p == null) throw new ServiceException("AI 项目不存在");
        return p;
    }

    private AiTask requiredTask(Long id)
    {
        if (id == null) throw new ServiceException("任务 id 不能为空");
        AiTask task = taskMapper.selectById(id);
        if (task == null) throw new ServiceException("AI 任务不存在");
        return task;
    }

    private AiAttempt requiredAttempt(Long id)
    {
        if (id == null) throw new ServiceException("attemptId 不能为空");
        AiAttempt a = attemptMapper.selectById(id);
        if (a == null) throw new ServiceException("执行记录不存在");
        return a;
    }

    private void event(Long taskId, Long attemptId, String type, String actorType, String actorId, Object data)
    {
        eventMapper.insert(taskId, attemptId, type, actorType, actorId,
            data == null ? null : JSON.toJSONString(data), new Date());
    }

    private String requiredGithubPr(Object value)
    {
        String url = requiredText(value, "PR 地址", 1000);
        if (!url.matches("^https://github\\.com/[^/]+/[^/]+/pull/[0-9]+(?:[/?#].*)?$"))
            throw new ServiceException("PR 地址必须是 GitHub pull request 链接");
        return url;
    }

    private String validateRepo(String repo)
    {
        if (!(repo.matches("^https://github\\.com/[^/]+/[^/]+(?:\\.git)?$") || repo.matches("^git@github\\.com:[^/]+/[^/]+(?:\\.git)?$")))
            throw new ServiceException("第一版只允许不含凭证的 GitHub 仓库地址");
        return repo;
    }

    private static String sha256(String value)
    {
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        }
        catch (Exception e)
        {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String normalizePrompt(String prompt)
    {
        return prompt.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private static Object value(Map<String, Object> body, Object old, String key, Object fallback)
    {
        return body.containsKey(key) ? body.get(key) : fallback;
    }

    private static String requiredText(Object value, String label, int max)
    {
        String text = value == null ? null : value.toString().trim();
        if (StringUtils.isEmpty(text)) throw new ServiceException(label + "不能为空");
        if (text.length() > max) throw new ServiceException(label + "不能超过 " + max + " 个字符");
        return text;
    }

    private static String text(Object value, String fallback, int max)
    {
        if (value == null) return fallback;
        String text = value.toString().trim();
        if (text.length() > max) throw new ServiceException("文本不能超过 " + max + " 个字符");
        return text.length() == 0 ? fallback : text;
    }

    private static String trim(String value)
    {
        return value == null ? null : value.trim();
    }

    private static Long longValue(Object value)
    {
        if (value == null || value.toString().trim().length() == 0) return null;
        try { return Long.valueOf(value.toString()); }
        catch (Exception e) { throw new ServiceException("数字参数格式错误"); }
    }

    private static Integer integer(Object value, Integer fallback)
    {
        if (value == null || value.toString().trim().length() == 0) return fallback;
        try { return Integer.valueOf(value.toString()); }
        catch (Exception e) { throw new ServiceException("整数参数格式错误"); }
    }

    private static Boolean bool(Object value)
    {
        if (value == null) return null;
        if (value instanceof Boolean) return (Boolean) value;
        String v = value.toString();
        return "1".equals(v) || "true".equalsIgnoreCase(v) || "yes".equalsIgnoreCase(v);
    }

    private static String enumValue(Object value, String fallback, String... allowed)
    {
        String actual = value == null ? fallback : value.toString().trim();
        for (String item : allowed) if (item.equals(actual)) return actual;
        throw new ServiceException("不支持的枚举值：" + actual);
    }

    private static String jsonValue(Object value, String fallback)
    {
        if (value == null) return fallback;
        if (value instanceof String)
        {
            String s = value.toString().trim();
            if (s.length() == 0) return fallback;
            try { JSON.parse(s); return s; }
            catch (Exception e) { throw new ServiceException("JSON 配置格式错误"); }
        }
        return JSON.toJSONString(value);
    }

    private static String jsonArrayValue(Object value, String fallback, boolean allowEmpty)
    {
        String json = jsonValue(value, fallback);
        Object parsed;
        try { parsed = JSON.parse(json); }
        catch (Exception e) { throw new ServiceException("JSON 数组格式错误"); }
        if (!(parsed instanceof List)) throw new ServiceException("配置必须是 JSON 数组");
        if (!allowEmpty && ((List<?>) parsed).isEmpty()) throw new ServiceException("至少需要一条确定性验证命令");
        return json;
    }

    private static String shaValue(Object value, String label)
    {
        String sha = requiredText(value, label, 64);
        if (!sha.matches("^[0-9a-fA-F]{40,64}$")) throw new ServiceException(label + " 格式错误");
        return sha.toLowerCase();
    }

    private static Object parseJsonOrString(String value)
    {
        if (value == null) return new ArrayList<>();
        try { return JSON.parse(value); }
        catch (Exception e) { return value; }
    }

    private static Map<String, Object> mapOf(Object... values)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) out.put(String.valueOf(values[i]), values[i + 1]);
        return out;
    }
}
