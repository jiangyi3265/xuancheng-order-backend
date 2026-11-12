package com.ruoyi.jiedan.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.support.TransactionTemplate;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import org.apache.commons.codec.digest.DigestUtils;
import com.alibaba.fastjson2.JSONArray;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.jiedan.service.NoticeTarget;
import com.ruoyi.jiedan.domain.JiedanBug;
import com.ruoyi.jiedan.domain.JiedanBugUpdate;
import com.ruoyi.jiedan.domain.JiedanOrder;
import com.ruoyi.jiedan.domain.JiedanPayment;
import com.ruoyi.jiedan.domain.JiedanSetting;
import com.ruoyi.jiedan.domain.JiedanTimeline;
import com.ruoyi.jiedan.mapper.JiedanAccountMapper;
import com.ruoyi.jiedan.mapper.JiedanApprovalMapper;
import com.ruoyi.jiedan.mapper.JiedanBugMapper;
import com.ruoyi.jiedan.mapper.JiedanIssueReviewMapper;
import com.ruoyi.jiedan.mapper.JiedanBugUpdateMapper;
import com.ruoyi.jiedan.mapper.JiedanMentionMapper;
import com.ruoyi.jiedan.mapper.JiedanOrderMapper;
import com.ruoyi.jiedan.mapper.JiedanProjectTesterMapper;
import com.ruoyi.jiedan.mapper.JiedanPaymentMapper;
import com.ruoyi.jiedan.mapper.JiedanSettingMapper;
import com.ruoyi.jiedan.mapper.JiedanTimelineMapper;
import com.ruoyi.jiedan.service.IJiedanOrderService;
import com.ruoyi.jiedan.service.JiedanAccess;
import com.ruoyi.jiedan.service.IssueSheet;
import com.ruoyi.jiedan.service.JiedanAttachmentStorage;
import com.ruoyi.jiedan.service.JiedanAuditService;
import com.ruoyi.jiedan.service.JiedanPushService;

/**
 * 接单订单服务。
 * 管理端方法一律先过 guard()：总管理员看全部，下属管理员只能动负责人是自己的项目；
 * “操作人是谁”取自登录令牌，不再信任前端传来的 byMemberId / byUserName。
 * 项目的相关人 = 负责人 + 全体总管理员，未读标记与推送都只发给这些人。
 */
@Service
public class JiedanOrderServiceImpl implements IJiedanOrderService
{
    @Autowired
    private JiedanOrderMapper orderMapper;

    @Autowired
    private JiedanProjectTesterMapper testers;

    @Autowired
    private JiedanTimelineMapper timelineMapper;

    @Autowired
    private JiedanPaymentMapper paymentMapper;

    @Autowired
    private JiedanApprovalMapper approvalMapper;

    @Autowired
    private JiedanBugMapper bugMapper;

    @Autowired
    private JiedanIssueReviewMapper issueReviews;

    @Autowired
    private JiedanBugUpdateMapper bugUpdateMapper;

    @Autowired
    private JiedanSettingMapper settingMapper;

    @Autowired
    private JiedanPushService pushService;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JiedanAttachmentStorage attachmentStorage;

    @Autowired
    private JiedanAccess access;

    @Autowired
    private JiedanAuditService audit;

    @Autowired
    private JiedanAccountMapper accountMapper;

    @Autowired
    private JiedanMentionMapper mentionMapper;

    private static final Map<String, String> PRIORITY_LABELS = new LinkedHashMap<String, String>()
    {{
        put("high", "高");
        put("medium", "中");
        put("low", "低");
    }};

    private static final Map<String, String> STATUS_LABELS = new LinkedHashMap<String, String>()
    {{
        put("pending", "待确认");
        put("doing", "进行中");
        put("review", "待验收");
        put("done", "已完成");
        put("cancelled", "已取消");
    }};

    // ---------- 查询（管理端，按身份过滤） ----------
    @Override
    public List<Map<String, Object>> listVO()
    {
        List<Map<String, Object>> list = new ArrayList<>();
        Map<Long, String> names = new LinkedHashMap<>();
        Map<Long,List<Map<String,Object>>> approvals = recentHandoffApprovals();
        for (Map<String, Object> row : orderMapper.selectAdminSummaries(access.ownerScope(), access.currentUserId()))
        {
            Map<String, Object> vo = toAdminListSummaryVO(row);
            vo.put("recentApprovalEvents", approvals.getOrDefault(asLong(row.get("id")), List.of()));
            Long nextOwner = asLong(row.get("nextOwnerId"));
            // 员工的成员列表里只有自己：下一步交给老板时，名字由这里带过去
            if (nextOwner != null) vo.put("nextOwnerName", names.computeIfAbsent(nextOwner, this::ownerName));
            list.add(vo);
        }
        return list;
    }

    @Override
    public Map<String, Object> getVO(Long id)
    {
        return toVO(guard(id), true);
    }

    @Override
    public Map<String, Object> getVersion(Long id)
    {
        return versionVO(id, orderMapper.selectVersionScoped(id, access.ownerScope()));
    }

    @Override
    public String stamp()
    {
        return orderMapper.selectStamp(access.ownerScope());
    }

    /** 列表筛选（导出用）：在“我能看到的项目”里按条件过滤，条件与后台列表页的筛选一致。 */
    @Override
    public List<Map<String, Object>> listFiltered(Map<String, String> q)
    {
        String keyword = strOr(q.get("keyword"), "").trim().toLowerCase();
        String channel = strOr(q.get("channel"), "");
        String status = strOr(q.get("status"), "");
        Long ownerId = asLong(q.get("ownerId"));
        String paid = strOr(q.get("paid"), "");
        boolean overdueOnly = "1".equals(q.get("overdue"));
        String createFrom = strOr(q.get("createFrom"), "");
        String createTo = strOr(q.get("createTo"), "");
        String deadlineFrom = strOr(q.get("deadlineFrom"), "");
        String deadlineTo = strOr(q.get("deadlineTo"), "");
        String today = LocalDate.now().toString();

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> vo : listVO())
        {
            String st = asStr(vo.get("status"));
            String dl = strOr(asStr(vo.get("deadline")), "");
            String created = strOr(asStr(vo.get("createTime")), "");
            String createDay = created.length() >= 10 ? created.substring(0, 10) : created;

            if (!channel.isEmpty() && !channel.equals(asStr(vo.get("channel")))) continue;
            if (!status.isEmpty() && !status.equals(st)) continue;
            if ("1".equals(q.get("urgentOnly")) && vo.get("urgentUntil") == null) continue;
            if (ownerId != null && !ownerId.equals(asLong(vo.get("ownerId")))) continue;
            // 收款：1=已收清 0=还欠款（报价大于已收） part=收了一部分；“欠款”类不含已取消的项目（取消了就不再追款）
            BigDecimal amt = asDecimal(vo.get("amount"));
            BigDecimal rec = asDecimal(vo.get("receivedAmount"));
            boolean full = isFullyPaid(rec, amt);
            boolean cancelled = "cancelled".equals(st);
            if ("1".equals(paid) && !full) continue;
            if ("0".equals(paid) && (cancelled || amt.compareTo(rec) <= 0)) continue;
            if ("part".equals(paid) && (cancelled || !(rec.signum() > 0 && !full))) continue;
            if (overdueOnly && !(Arrays.asList("pending", "doing", "review").contains(st)
                    && !dl.isEmpty() && dl.compareTo(today) < 0)) continue;
            if (!createFrom.isEmpty() && createDay.compareTo(createFrom) < 0) continue;
            if (!createTo.isEmpty() && createDay.compareTo(createTo) > 0) continue;
            if (!deadlineFrom.isEmpty() && (dl.isEmpty() || dl.compareTo(deadlineFrom) < 0)) continue;
            if (!deadlineTo.isEmpty() && (dl.isEmpty() || dl.compareTo(deadlineTo) > 0)) continue;
            if (!keyword.isEmpty())
            {
                String hay = (strOr(asStr(vo.get("title")), "") + " " + strOr(asStr(vo.get("customer")), "")
                        + " " + strOr(asStr(vo.get("orderNo")), "") + " " + strOr(asStr(vo.get("contact")), "")
                        + " " + strOr(asStr(vo.get("requirement")), "")).toLowerCase();
                if (!hay.contains(keyword)) continue;
            }
            result.add(vo);
        }
        return result;
    }

    // ---------- 客户门户：仅限本人项目 ----------
    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listByCustomer(String account)
    {
        List<Map<String, Object>> list = new ArrayList<>();
        if (account == null || account.trim().isEmpty()) return list;
        Map<Long, List<Map<String, Object>>> byProject = new LinkedHashMap<>();
        for (Map<String, Object> item : bugMapper.customerRequirements(account, SecurityUtils.getUserId()))
        {
            if ("confirm".equals(item.get("status")) && item.get("reviewId") == null)
                item.put("status", "confirm_other");
            customerIssuePreview(item);
            byProject.computeIfAbsent(asLong(item.get("orderId")), id -> new ArrayList<>()).add(item);
        }
        for (Map<String, Object> row : orderMapper.selectCustomerSummaries(account))
        {
            Map<String, Object> summary = toListSummaryVO(row);
            List<Map<String, Object>> requirements = byProject.getOrDefault(asLong(row.get("id")), java.util.Collections.emptyList());
            summary.put("isPrimaryCustomer", account.equals(row.get("customerAccount")));
            summary.put("requirements", requirements);
            summary.put("pendingReviewCount", requirements.stream().filter(item -> item.get("reviewId") != null).count());
            list.add(summary);
        }
        return list;
    }

    private void customerIssuePreview(Map<String, Object> item)
    {
        String raw = asStr(item.remove("rawAttachments"));
        JSONArray files = normalizedAttachments(raw, "bug", asLong(item.get("id")));
        Object first = null;
        int count = 0;
        for (Object value : files)
        {
            if (!(value instanceof Map)) continue;
            Map<?, ?> file = (Map<?, ?>) value;
            if (!"image".equals(file.get("type")) || Objects.toString(file.get("url"), "").isEmpty()) continue;
            count++;
            if (first == null)
            {
                Map<String, Object> preview = new LinkedHashMap<>();
                for (String field : Arrays.asList("url", "name", "size", "type", "ext"))
                    if (file.get(field) != null) preview.put(field, file.get(field));
                first = preview;
            }
        }
        item.put("firstImage", first);
        item.put("imageCount", count);
        Long replyId = asLong(item.remove("replyId"));
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("id", replyId);
        reply.put("content", Objects.toString(item.remove("replyContent"), ""));
        reply.put("time", item.remove("replyTime"));
        reply.put("hasAttachments", asLong(item.remove("replyFiles")) != 0L);
        long seenId = asLong(item.remove("seenId"));
        reply.put("unread", replyId != null && replyId > seenId);
        item.put("latestTeamReply", replyId == null ? null : reply);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean markCustomerReplySeen(Long orderId, Long bugId, Long updateId, String account)
    {
        if (StringUtils.isEmpty(account)) return false;
        JiedanOrder order = issueReviews.lockProject(bugId, null, account);
        if (order == null || !orderId.equals(order.getId()) || !bugUpdateMapper.isTeamReply(bugId, updateId)) return false;
        bugUpdateMapper.markSeen(SecurityUtils.getUserId(), bugId, updateId);
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getForCustomer(Long id, String account)
    {
        JiedanOrder o = orderMapper.selectById(id);
        if (!canViewProject(o, account)) return null;
        return toVO(o, false);
    }

    @Override
    public Map<String, Object> getVersionForCustomer(Long id, String account)
    {
        return versionVO(id, orderMapper.selectVersionForCustomer(id, account));
    }

    // ---------- 客户留言：负责人与总管理员同时收到并标记未读 ----------
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> customerMessage(Long orderId, String account, String customerName,
                                               String content, Object attachments)
    {
        JiedanOrder o = orderMapper.selectScopedForUpdate(orderId, null);
        if (!canViewProject(o, account)) return null;
        Date now = new Date();
        String name = strOr(customerName, strOr(o.getCustomer(), "客户"));
        Long entryId = addTimeline(orderId, name, "message", content, jsonStr(attachments), now);
        markUnreadForCustomerAction(o, now);
        String brief = strOr(content, "[消息]");
        pushRecipients(o, null, "💬 客户留言", o.getTitle() + "：" + brief + "（" + name + "）", NoticeTarget.timeline(orderId, entryId, "message"));
        return getForCustomer(orderId, account);
    }

    /**
     * 客户确认验收：以前客户只能看到「验收中」，要员工在微信里问一句再手动改状态。
     * 现在客户自己点「确认验收」，项目直接变成「已完成」，负责人和老板都会收到提醒（还有尾款没收的顺带写上）。
     */
    @Override
    @Transactional
    public Map<String, Object> acceptForCustomer(Long orderId, String account, String customerName)
    {
        JiedanOrder o = orderMapper.selectScopedForUpdate(orderId, null);
        if (!canViewProject(o, account)) return null;
        // 连点两次 / 别的设备已经确认过：直接返回最新详情，不重复记录
        if ("done".equals(o.getStatus())) return getForCustomer(orderId, account);
        if (!"review".equals(o.getStatus())) throw new ServiceException("项目还没提交验收，暂时不能确认");
        Date now = new Date();
        String name = strOr(customerName, strOr(o.getCustomer(), "客户"));

        JiedanOrder upd = new JiedanOrder();
        upd.setId(orderId);
        upd.setStatus("done");
        upd.setUnread(join(recipientIds(o.getOwnerId())));
        upd.setUpdateTime(now);
        orderMapper.update(upd);

        approvalReceipt(o,"delivery",taskTurn(orderId,"delivery"),name);
        issueReviews.cancelForProject(orderId);
        endUrgentHandoff(orderId);
        addTimeline(orderId, name, "status", "确认验收，项目已完成", null, now);
        audit.record("order.status", "order", orderId, o.getTitle(), "客户确认验收：待验收 → 已完成");
        BigDecimal amount = o.getAmount() == null ? BigDecimal.ZERO : o.getAmount();
        BigDecimal received = o.getReceivedAmount() == null ? BigDecimal.ZERO : o.getReceivedAmount();
        BigDecimal owed = amount.subtract(received);
        String tail = owed.signum() > 0 ? "，还有 ¥" + owed.stripTrailingZeros().toPlainString() + " 未收" : "";
        pushRecipients(o, null, "✅ 客户已确认验收", o.getTitle() + "（" + name + "）" + tail);
        return getForCustomer(orderId, account);
    }

    // ---------- 新增（建单即派单 + 推送） ----------
    @Override
    @Transactional
    public Map<String, Object> create(Map<String, Object> p)
    {
        Long by = access.currentUserId();
        Long owner = by;
        boolean needAccept = false;
        if (access.isBoss())
        {
            Long requested = asLong(p.get("ownerId"));
            if (requested != null)
            {
                if (!access.isAssignable(requested)) throw new ServiceException("负责人无效或账号已停用");
                owner = requested;
                // 老板把项目直接建给员工：员工要先接收（或拒绝）
                needAccept = !access.bossUserIds().contains(requested);
            }
        }
        String customerAccount = asStr(p.get("customerAccount"));
        return insertOrder(p, owner, customerAccount, by, access.currentName(), needAccept);
    }

    /** 客户端提交需求：自动落到该客户负责人的名下。 */
    @Override
    @Transactional
    public Map<String, Object> createFromCustomer(Map<String, Object> p, String account)
    {
        Long customerId = SecurityUtils.getUserId();
        Object keyValue = p.get("submissionKey");
        if (keyValue != null && (!(keyValue instanceof String) || !((String) keyValue).matches("[A-Za-z0-9_-]{16,64}")))
            throw new ServiceException("提交凭据无效，请重新打开需求表单");
        String key = (String) keyValue;
        if (key == null) return addCustomerRequirement(p, account);

        Map<String, Object> fields = new LinkedHashMap<>();
        for (String field : Arrays.asList("title", "customer", "contact", "requirement", "attachments", "priority", "deadline"))
            fields.put(field, p.get(field));
        if (p.get("orderId") != null) fields.put("orderId", asLong(p.get("orderId")));
        fields.put("amount", asDecimal(p.get("amount")).stripTrailingZeros().toPlainString());
        String hash = DigestUtils.sha256Hex(JSON.toJSONString(fields, JSONWriter.Feature.SortMapEntriesByKeys));

        // The unique reservation and the order commit together. A concurrent retry waits for the first transaction.
        orderMapper.reserveIntake(customerId, key, hash);
        Map<String, Object> receipt = orderMapper.lockIntake(customerId, key, account);
        if (receipt.get("orderId") != null)
        {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("replayed", true);
            result.put("changed", !hash.equals(receipt.get("payloadHash")));
            if (receipt.get("visibleId") != null && (receipt.get("bugId") == null || receipt.get("visibleBugId") != null))
            {
                result.put("id", receipt.get("visibleId"));
                result.put("bugId", receipt.get("visibleBugId"));
                result.put("orderNo", receipt.get("orderNo"));
                result.put("title", receipt.get("title"));
                result.put("createTime", fmtIfDate(receipt.get("created")));
            }
            return result;
        }

        Map<String, Object> result = addCustomerRequirement(p, account);
        orderMapper.finishIntake(customerId, key, asLong(result.get("id")), asLong(result.get("bugId")));
        return result;
    }

    private Map<String, Object> addCustomerRequirement(Map<String, Object> p, String account)
    {
        Long projectId = asLong(p.get("orderId"));
        if (p.get("orderId") == null)
        {
            List<Long> ids = orderMapper.customerProjectIds(account);
            if (ids.isEmpty()) throw new ServiceException("请先联系团队绑定项目");
            if (ids.size() != 1) throw new ServiceException("请选择这条需求所属的项目");
            projectId = ids.get(0);
        }
        JiedanOrder project = orderMapper.selectScopedForUpdate(projectId, null);
        if (!canViewProject(project, account))
            throw new ServiceException("项目不存在或已解除绑定，请重新选择项目");

        String title = java.util.Objects.toString(p.get("title"), "").trim();
        String description = java.util.Objects.toString(p.get("requirement"), "").trim();
        String contact = java.util.Objects.toString(p.get("contact"), "").trim();
        if (title.codePointCount(0, title.length()) > 255 || contact.codePointCount(0, contact.length()) > 100)
            throw new ServiceException("请缩短需求标题或联系方式");
        if (title.isEmpty() && isBlankBug(description, p.get("attachments")))
            throw new ServiceException("请填写需求或添加附件");
        List<String> lines = new ArrayList<>();
        if (!title.isEmpty()) lines.add(title);
        if (!description.isEmpty() && !description.equals(title)) lines.add(description);
        int publicLength = String.join("\n", lines).length();
        if (lines.isEmpty()) lines.add("附件需求");
        if (!contact.isEmpty()) lines.add("联系方式：" + contact);
        if (asDecimal(p.get("amount")).signum() > 0) lines.add("参考预算：¥" + money(asDecimal(p.get("amount"))));
        String content = String.join("\n", lines);
        if (content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65535)
            throw new ServiceException("需求说明太长，请将详细内容放在附件里");

        Date now = new Date();
        String author = publicCustomerName(SecurityUtils.getLoginUser().getUser().getNickName(), account);
        JiedanBug bug = insertBug(projectId, author, content, p.get("attachments"), now, "customer", publicLength, false);
        markUnreadForCustomerAction(project, now);
        notifyNewBug(project, bug, null);
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("id", projectId);
        receipt.put("orderNo", project.getOrderNo());
        receipt.put("title", project.getTitle());
        receipt.put("bugId", bug.getId());
        receipt.put("createTime", fmt(now));
        return receipt;
    }

    private Map<String, Object> insertOrder(Map<String, Object> p, Long ownerId, String customerAccount,
                                            Long by, String byName, boolean needAccept)
    {
        Date now = new Date();
        JiedanOrder o = new JiedanOrder();
        o.setTitle(strOr(asStr(p.get("title")), "未命名需求"));
        o.setChannel(strOr(asStr(p.get("channel")), "wechat"));
        o.setCustomer(asStr(p.get("customer")));
        o.setCustomerAccount(customerAccount);
        o.setContact(asStr(p.get("contact")));
        o.setAmount(asDecimal(p.get("amount")));
        o.setOwnerId(ownerId);
        o.setStatus(checkStatus(strOr(asStr(p.get("status")), "pending")));
        o.setPriority(strOr(asStr(p.get("priority")), "medium"));
        o.setDeadline(asStr(p.get("deadline")));
        o.setRequirement(asStr(p.get("requirement")));
        o.setAttachments(jsonStr(p.get("attachments")));
        o.setUnread(join(othersThan(ownerId, by)));
        o.setPaid(0);
        o.setReceivedAmount(BigDecimal.ZERO);
        o.setAssignState(needAccept ? "pending" : "");
        o.setRevisions(0);
        o.setCreateTime(now);
        o.setUpdateTime(now);
        orderMapper.insert(o);
        access.claimProjectDraft(o.getId(), p.get("customerDraftKey"));
        access.requireBindableCustomer(customerAccount, o.getId());
        if (p.containsKey("testerIds")) saveTesters(o.getId(), customerAccount, p.get("testerIds"), List.of());

        o.setOrderNo("XC-" + year(now) + "-" + String.format("%04d", o.getId()));
        JiedanOrder upd = new JiedanOrder();
        upd.setId(o.getId());
        upd.setOrderNo(o.getOrderNo());
        orderMapper.update(upd);

        addTimeline(o.getId(), byName, "create", "创建订单", null, now);
        pushRecipients(o, by, "🆕 新单待接", o.getTitle() + dash(o.getCustomer()) + "（来自 " + byName + "）");

        String amountText = o.getAmount() != null && o.getAmount().signum() > 0 ? "；金额：" + money(o.getAmount()) : "";
        audit.record("order.create", "order", o.getId(), o.getTitle(),
                (by == null ? "客户端提交" : "后台建单") + "；负责人：" + ownerName(ownerId) + amountText);
        return voOf(o.getId());
    }

    private boolean canViewProject(JiedanOrder order, String account)
    {
        return order != null && !StringUtils.isEmpty(account)
                && (account.equals(order.getCustomerAccount()) || orderMapper.selectVersionForCustomer(order.getId(), account) != null);
    }

    private boolean canManageCustomerIssue(JiedanOrder order, JiedanBug bug, String account)
    {
        return account.equals(order.getCustomerAccount()) || SecurityUtils.getUserId().equals(bug.getCustomerAuthorId());
    }

    private Set<Long> testerIds(Object value)
    {
        if (!(value instanceof List)) throw new ServiceException("请选择有效的测试账号");
        Set<Long> ids = new LinkedHashSet<>();
        for (Object item : (List<?>) value)
        {
            Long id;
            try { id = Long.valueOf(String.valueOf(item)); }
            catch (NumberFormatException e) { throw new ServiceException("测试账号无效"); }
            if (id <= 0) throw new ServiceException("测试账号无效");
            ids.add(id);
        }
        return ids;
    }

    // Caller holds the project lock. Validate every new membership before changing the group.
    private void saveTesters(Long orderId, String primary, Object requested, List<Long> existing)
    {
        Set<Long> selected = testerIds(requested);
        if (!selected.isEmpty() && StringUtils.isEmpty(primary)) throw new ServiceException("请先绑定主客户账号");
        for (Long id : selected)
        {
            if (existing.contains(id)) continue;
            String username = testers.activeCustomer(id);
            if (username == null) throw new ServiceException("请选择已启用的客户账号");
            if (username.equalsIgnoreCase(primary)) throw new ServiceException("主客户账号已参与此项目");
            access.requireBindableCustomer(username, orderId);
        }
        for (Long id : existing) if (!selected.contains(id)) testers.remove(orderId, id);
        for (Long id : selected) if (!existing.contains(id)) testers.add(orderId, id);
    }

    // ---------- 修改 ----------
    @Override
    @Transactional
    public Map<String, Object> update(Map<String, Object> p)
    {
        Long id = asLong(p.get("id"));
        JiedanOrder cur = orderMapper.selectScopedForUpdate(id, access.ownerScope());
        if (cur == null) throw new ServiceException(JiedanAccess.DENIED);
        Long by = access.currentUserId();
        boolean boss = access.isBoss();

        JiedanOrder o = new JiedanOrder();
        o.setId(id);
        if (p.containsKey("title")) o.setTitle(asStr(p.get("title")));
        if (p.containsKey("channel")) o.setChannel(asStr(p.get("channel")));
        if (p.containsKey("customer")) o.setCustomer(asStr(p.get("customer")));
        if (p.containsKey("customerAccount"))
        {
            String account = strOr(asStr(p.get("customerAccount")), "");
            if (!Objects.equals(strOr(account, ""), strOr(cur.getCustomerAccount(), "")))
            {
                access.requireBindableCustomer(account, id);
            }
            o.setCustomerAccount(account);
        }
        if (p.containsKey("contact")) o.setContact(asStr(p.get("contact")));
        if (!boss)
        {
            // 员工的敏感操作要先走申请：这里只放行“没变”的值（编辑窗口会把原值一起带回来）
            if (p.containsKey("amount"))
            {
                BigDecimal curAmt = cur.getAmount() == null ? BigDecimal.ZERO : cur.getAmount();
                if (asDecimal(p.get("amount")).compareTo(curAmt) != 0)
                {
                    throw new ServiceException("改报价要先提交申请，审批通过后才生效");
                }
            }
            String wanted = p.containsKey("status") ? asStr(p.get("status")) : null;
            if (wanted != null && !wanted.equals(cur.getStatus())) staffStatusGate(cur, wanted);
        }
        if (p.containsKey("amount"))
        {
            o.setAmount(asDecimal(p.get("amount")));
            // 改了报价，“是否已收清”要跟着重新算（已收金额不变）
            o.setPaid(isFullyPaid(cur.getReceivedAmount(), o.getAmount()) ? 1 : 0);
        }

        Long newOwner = cur.getOwnerId();
        if (p.containsKey("ownerId"))
        {
            Long requested = asLong(p.get("ownerId"));
            if (requested != null && !requested.equals(cur.getOwnerId()))
            {
                if (!boss) throw new ServiceException("当前账号不能转派项目");
                if (!access.isAssignable(requested)) throw new ServiceException("负责人无效或账号已停用");
                o.setOwnerId(requested);
                newOwner = requested;
                // 派给员工：等 TA 接收或拒绝；转回老板自己名下：无需接收
                o.setAssignState(access.bossUserIds().contains(requested) ? "" : "pending");
            }
        }
        if (p.containsKey("status") && asStr(p.get("status")) != null) o.setStatus(checkStatus(asStr(p.get("status"))));
        if (p.containsKey("priority")) o.setPriority(asStr(p.get("priority")));
        if (p.containsKey("deadline")) o.setDeadline(asStr(p.get("deadline")));
        if (p.containsKey("requirement")) o.setRequirement(asStr(p.get("requirement")));
        if (p.containsKey("attachments")) o.setAttachments(jsonStr(p.get("attachments")));
        // 收款不走这里：已收金额只由「收款流水」（addPayment / deletePayment）推算，前端传来的 paid / receivedAmount 一律忽略
        o.setUnread(join(othersThan(newOwner, by)));
        o.setUpdateTime(new Date());
        List<String> changes = describeChanges(cur, o);
        boolean primaryChanged = o.getCustomerAccount() != null &&
                !Objects.equals(o.getCustomerAccount(), strOr(cur.getCustomerAccount(), ""));
        if (primaryChanged || p.containsKey("testerIds"))
        {
            List<Long> existing = testers.list(id);
            if (p.containsKey("previousTesterIds") && !testerIds(p.get("previousTesterIds")).equals(new LinkedHashSet<>(existing))
                    || p.containsKey("previousCustomerAccount") && !Objects.equals(strOr(asStr(p.get("previousCustomerAccount")), ""), strOr(cur.getCustomerAccount(), "")))
                throw new ServiceException("客户绑定已被修改，请重新打开项目后再保存");
            if (primaryChanged)
            {
                for (Long tester : existing) testers.remove(id, tester);
                existing = List.of();
            }
            saveTesters(id, primaryChanged ? o.getCustomerAccount() : cur.getCustomerAccount(),
                    p.getOrDefault("testerIds", List.of()), existing);
            changes.add("更新客户绑定");
        }
        orderMapper.update(o);
        updateDelivery(cur,newOwner,o.getStatus(),p);
        if ((o.getCustomerAccount() != null && !Objects.equals(o.getCustomerAccount(), cur.getCustomerAccount()))
                || closedStatus(o.getStatus())) issueReviews.cancelForProject(id);

        if (closedStatus(o.getStatus())) endUrgentHandoff(id);
        if (newOwner != null && !newOwner.equals(cur.getOwnerId()))
        {
            retargetNextStep(cur, newOwner);
            if (!newOwner.equals(by))
            {
                pushService.pushToMember(newOwner, "📌 项目已派给你，请接收或拒绝", cur.getTitle() + dash(cur.getCustomer()), id);
            }
        }
        if (!changes.isEmpty())
        {
            audit.record("order.update", "order", id, cur.getTitle(), String.join("；", changes));
        }
        return voOf(id);
    }

    /** 把这次修改里“值得留痕”的字段整理成人话：旧值 → 新值。 */
    private List<String> describeChanges(JiedanOrder cur, JiedanOrder next)
    {
        List<String> out = new ArrayList<>();
        diffText(out, "项目名", cur.getTitle(), next.getTitle());
        diffText(out, "客户", cur.getCustomer(), next.getCustomer());
        diffText(out, "客户账号", cur.getCustomerAccount(), next.getCustomerAccount());
        if (next.getAmount() != null
                && (cur.getAmount() == null || cur.getAmount().compareTo(next.getAmount()) != 0))
        {
            out.add("金额 " + money(cur.getAmount()) + " → " + money(next.getAmount()));
        }
        if (next.getOwnerId() != null && !next.getOwnerId().equals(cur.getOwnerId()))
        {
            out.add("负责人 " + ownerName(cur.getOwnerId()) + " → " + ownerName(next.getOwnerId()));
        }
        if (next.getStatus() != null && !next.getStatus().equals(cur.getStatus()))
        {
            out.add("状态 " + STATUS_LABELS.getOrDefault(cur.getStatus(), strOr(cur.getStatus(), "无"))
                    + " → " + STATUS_LABELS.getOrDefault(next.getStatus(), next.getStatus()));
        }
        if (next.getPriority() != null && !next.getPriority().equals(cur.getPriority()))
        {
            out.add("优先级 " + PRIORITY_LABELS.getOrDefault(cur.getPriority(), strOr(cur.getPriority(), "无"))
                    + " → " + PRIORITY_LABELS.getOrDefault(next.getPriority(), next.getPriority()));
        }
        if (next.getDeadline() != null
                && !strOr(next.getDeadline(), "").equals(strOr(cur.getDeadline(), "")))
        {
            out.add("截止 " + strOr(cur.getDeadline(), "无") + " → " + strOr(next.getDeadline(), "无"));
        }
        if (next.getPaid() != null && !next.getPaid().equals(cur.getPaid() == null ? 0 : cur.getPaid()))
        {
            out.add("收款 " + (cur.getPaid() != null && cur.getPaid() == 1 ? "已收款" : "未收款")
                    + " → " + (next.getPaid() == 1 ? "已收款" : "未收款"));
        }
        if (next.getRequirement() != null && !strOr(next.getRequirement(), "").equals(strOr(cur.getRequirement(), "")))
        {
            out.add("需求说明已修改");
        }
        return out;
    }

    private void diffText(List<String> out, String label, String before, String after)
    {
        if (after != null && !after.equals(strOr(before, "")))
        {
            out.add(label + " " + strOr(before, "无") + " → " + strOr(after, "无"));
        }
    }

    // ---------- 删除：放进回收站，可恢复 ----------
    @Override
    @Transactional
    public void delete(Long id)
    {
        if (!access.isBoss()) throw new ServiceException("删除项目要先提交申请，审批通过后才生效");
        JiedanOrder o = guard(id);
        Long by = access.currentUserId();
        String byName = access.currentName();
        orderMapper.softDelete(id, byName, by, new Date());
        orderMapper.disableAgentLink(id);
        audit.record("order.delete", "order", id, o.getTitle(), "移入回收站");
        pushRecipients(o, by, "🗑 项目已删除", o.getTitle() + dash(o.getCustomer()) + "（" + byName + "，可在回收站恢复）");
    }

    /**
     * 客户只能撤回还没开始处理的问题（待确认）；一旦团队已经接手，就不能自行删除，
     * 避免聊天记录、Bug 和内部记事本被客户一键清空。删除同样进回收站，团队可恢复。
     */
    @Override
    @Transactional
    public boolean deleteForCustomer(Long id, String account)
    {
        JiedanOrder o = orderMapper.selectScopedForUpdate(id, null);
        if (o == null || account == null || !account.equals(o.getCustomerAccount())) return false;
        if (!"pending".equals(o.getStatus()))
        {
            throw new ServiceException("该项目已在处理中，不能自行删除，如需撤销请在项目里留言联系我们");
        }
        if (!"form".equals(o.getChannel()) || !bugMapper.selectByOrderId(id).isEmpty())
            throw new ServiceException("请在修改需求中选择要撤回的那一条");
        String byName = "客户：" + access.currentName();
        orderMapper.softDelete(id, byName, null, new Date());
        orderMapper.disableAgentLink(id);
        audit.record("order.delete", "order", id, o.getTitle(), "客户撤回（移入回收站）");
        pushRecipients(o, null, "🗑 客户撤回了项目", o.getTitle() + dash(o.getCustomer()));
        return true;
    }

    // ---------- 回收站 ----------
    @Override
    public List<Map<String, Object>> listTrash()
    {
        List<Map<String, Object>> rows = orderMapper.selectTrash(access.ownerScope());
        for (Map<String, Object> row : rows)
        {
            row.put("createTime", fmtIfDate(row.get("createTime")));
            row.put("deleteTime", fmtIfDate(row.get("deleteTime")));
            row.put("deleteBy", displayMemberName(asStr(row.get("deleteBy"))));
        }
        return rows;
    }

    @Override
    @Transactional
    public void restore(Long id)
    {
        JiedanOrder o = guardTrash(id);
        // 原负责人已被删除或停用时，恢复后改由恢复人接手，避免项目没人管
        Long newOwner = null;
        if (o.getOwnerId() == null || !access.isAssignable(o.getOwnerId())) newOwner = access.currentUserId();
        orderMapper.restore(id, newOwner, new Date());
        if (newOwner != null) retargetNextStep(o, newOwner);
        audit.record("order.restore", "order", id, o.getTitle(),
                "从回收站恢复" + (newOwner == null ? "" : "；原负责人不可用，改由 " + access.currentName() + " 负责"));
    }

    @Override
    @Transactional
    public void purge(Long id)
    {
        access.requireBoss();
        JiedanOrder o = guardTrash(id);
        doDelete(id);
        audit.record("order.purge", "order", id, o.getTitle(), "从回收站彻底删除");
    }

    /** 回收站里的项目：必须已删除，且当前登录人有权看。 */
    private JiedanOrder guardTrash(Long id)
    {
        JiedanOrder o = id == null ? null : orderMapper.selectByIdAny(id);
        if (o == null || o.getDelFlag() == null || o.getDelFlag() != 1 || !access.canAccess(o))
        {
            throw new ServiceException("回收站里没有这个项目");
        }
        return o;
    }

    /** 彻底删除一个项目及其所有留言、Bug、进度（不可恢复，只给回收站用）。 */
    private void doDelete(Long id)
    {
        approvalMapper.deleteByOrderId(id);
        paymentMapper.deleteByOrderId(id);
        bugUpdateMapper.deleteByOrderId(id);
        bugMapper.deleteByOrderId(id);
        orderMapper.clearReceipts(id, null);
        timelineMapper.deleteByOrderId(id);
        mentionMapper.deleteByOrderId(id);
        orderMapper.deleteById(id);
    }

    @Override
    @Transactional
    public Map<String, Object> createBug(Map<String, Object> p)
    {
        JiedanOrder o = guard(asLong(p.get("orderId")));
        if (isBlankBug(p.get("content"), p.get("attachments"))) return null;

        Long by = access.currentUserId();
        String byName = access.currentName();
        Date now = new Date();
        boolean sendToOwner = Boolean.TRUE.equals(p.get("needsAction"));
        if (sendToOwner && (o.getOwnerId() == null || "done".equals(o.getStatus()) || "cancelled".equals(o.getStatus())))
            throw new ServiceException("请先设置进行中的项目和负责人");
        JiedanBug bug = insertBug(o.getId(), byName, asStr(p.get("content")), p.get("attachments"), now, "team", null, sendToOwner);
        touchUnread(o, by, now);
        if (!by.equals(o.getOwnerId())) assignTaskTurn(o.getId(),"bug-"+bug.getId(),o.getOwnerId());
        if (sendToOwner)
            pushService.pushToTarget(o.getOwnerId(), "问题需处理 #" + bug.getId() + " · " + o.getTitle(),
                    excerpt(strOr(bug.getContent(), "请查看问题附件")), NoticeTarget.bug(o.getId(), bug.getId()));
        else notifyNewBug(o, bug, by);
        return voOf(o.getId());
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> deleteBug(Long bugId)
    {
        JiedanOrder o = issueReviews.lockProject(bugId, access.ownerScope(), null);
        if (o == null) throw new ServiceException(JiedanAccess.DENIED);
        JiedanBug bug = bugMapper.lockById(bugId);
        if (bug == null) return null;
        orderMapper.removeTaskTurn(o.getId(),"bug-"+bugId);
        bugUpdateMapper.deleteByBugId(bugId);
        bugMapper.deleteById(bugId);
        touchOrder(o.getId(), new Date());
        clearBugReceipt(bug);
        audit.record("bug.delete", "bug", bugId, o.getTitle(), "删除问题：" + excerpt(bug.getContent()));
        return voOf(o.getId());
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> addBugUpdate(Long bugId, Map<String, Object> p)
    {
        JiedanOrder o = issueReviews.lockProject(bugId, access.ownerScope(), null);
        if (o == null) throw new ServiceException(JiedanAccess.DENIED);
        JiedanBug bug = bugMapper.lockById(bugId);
        if (bug == null) return null;
        if (isBlankBug(p.get("content"), p.get("attachments"))) return null;

        Long by = access.currentUserId();
        String byName = access.currentName();
        Date now = new Date();
        JiedanBugUpdate reply = insertBugUpdate(bugId, byName, asStr(p.get("content")), p.get("attachments"), now);
        bugUpdateMapper.insertMeta(reply.getId(), bugId, "team", by, null, null);
        touchUnread(o, by, now);
        pushRecipients(o, by, "问题有补充说明", o.getTitle() + dash(byName), NoticeTarget.bug(o.getId(), bugId));
        return voOf(o.getId());
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> changeBugStatus(Long bugId, Map<String, Object> p)
    {
        JiedanOrder o = issueReviews.lockProject(bugId, access.ownerScope(), null);
        if (o == null) throw new ServiceException(JiedanAccess.DENIED);
        JiedanBug bug = bugMapper.lockById(bugId);
        if (bug == null) return null;
        requireNoPendingReview(bug);

        String key = "bug-"+bugId;
        Map<String,Object> turn = taskTurn(o.getId(),key);
        if (p != null && p.containsKey("submittedAt") && (turn == null
                || !Objects.equals(p.get("submittedAt"), turn.get("submittedAt"))
                || !Objects.equals(p.get("returnedAt"), turn.get("returnedAt"))))
            throw new ServiceException("这条问题有新的处理结果，请刷新后验收",409);
        if (p != null && p.containsKey("version") && !Objects.equals(o.getVersion(),asLong(p.get("version"))))
            throw new ServiceException("项目已变化，请刷新后再操作",409);
        String requested = p == null ? null : asStr(p.get("status"));
        if (p != null && p.get("previousStatus") != null && !Objects.equals(bug.getStatus(),p.get("previousStatus")))
            throw new ServiceException("问题状态已变化，请刷新后再操作",409);
        Long actor = access.currentUserId();
        if (turn != null && "action".equals(bug.getStatus()) && "open".equals(requested)
                && !actor.equals(asLong(turn.get("requesterId"))))
            throw new ServiceException("只有发起人可以撤回交办的问题",403);
        if (turn != null && ("action".equals(bug.getStatus()) || "open".equals(bug.getStatus()) && "submitReview".equals(requested))
                && ("resolved".equals(requested) || "submitReview".equals(requested))) {
            if (closedStatus(o.getStatus()) || "pending".equals(o.getAssignState())) throw new ServiceException("请先接收或重新开启项目",409);
            submitTaskTurn(o,key,o.getOwnerId(),turn,"问题 #"+bugId,NoticeTarget.bug(o.getId(),bugId),handoffSubmitNote(p));
            bugMapper.updateStatus(bugId,"ai_review");
            return voOf(o.getId());
        }
        if (turn != null && "ai_review".equals(bug.getStatus()) && !actor.equals(asLong(turn.get("requesterId"))))
            throw new ServiceException("请由发起人验收或退回",403);
        if ("ai_review".equals(bug.getStatus()) && ((turn == null && !access.isBoss()) || p == null || !List.of("resolved","action").contains(asStr(p.get("status")))))
            throw new ServiceException("请由管理员验收或退回修改");
        if (p != null && "submitReview".equals(p.get("status"))) {
            Long by = access.currentUserId();
            if (access.isBoss() || !by.equals(o.getOwnerId()))
                throw new ServiceException("请由项目负责人提交给同事验收", 403);
            if (closedStatus(o.getStatus()) || "pending".equals(o.getAssignState())
                    || !List.of("action","open").contains(strOr(bug.getStatus(), "open")))
                throw new ServiceException("这条问题当前不能提交验收", 409);
            Long reviewer = projectReviewer(o.getId(),null);
            if (reviewer == null) throw new ServiceException("请先设置可验收项目的账号");
            orderMapper.ensureTaskTurn(o.getId(),key,reviewer);
            recordTurnChange(o.getId(),key,"handoff.start");
            submitTaskTurn(o,key,o.getOwnerId(),taskTurn(o.getId(),key),"问题 #"+bugId, NoticeTarget.bug(o.getId(),bugId),handoffSubmitNote(p));
            bugMapper.updateStatus(bugId, "ai_review");
            clearBugReceipt(bug);
            return voOf(o.getId());
        }
        boolean needsAction = p != null && "action".equals(p.get("status"));
        if (needsAction && ("done".equals(o.getStatus()) || "cancelled".equals(o.getStatus())))
            throw new ServiceException("请先重新开启项目，再标记需处理");
        String status = needsAction ? "action" : normalizeBugStatus(p == null ? null : p.get("status"));
        boolean returning = needsAction && "ai_review".equals(bug.getStatus());
        String note = returning ? handoffReturnNote(p) : "";
        if ("action".equals(status)) {
            if (returning) {
                if (turn == null) assignTaskTurn(o.getId(),key,o.getOwnerId());
                returnTurn(o.getId(),key,note);
                addTimeline(o.getId(), access.currentName(), "handoff", "退回问题 #"+bugId+(note.isEmpty() ? "" : " · "+note), null, new Date());
            } else if (!"action".equals(bug.getStatus()) && (turn == null || !actor.equals(o.getOwnerId()))) assignTaskTurn(o.getId(),key,o.getOwnerId());
        } else {
            if ("ai_review".equals(bug.getStatus()) && "resolved".equals(status)) approvalReceipt(o,key,turn,access.currentName());
            orderMapper.removeTaskTurn(o.getId(),key);
        }
        bugMapper.updateStatus(bugId, status);

        Long by = access.currentUserId();
        String byName = access.currentName();
        touchUnread(o, by, new Date());
        if (!status.equals(bug.getStatus())) clearBugReceipt(bug);
        if (needsAction && o.getOwnerId() != null && !o.getOwnerId().equals(by))
            pushService.pushToTarget(o.getOwnerId(), returning ? "问题 #"+bugId+" 退回修改" : "问题需处理", o.getTitle() + "：" + excerpt(note.isEmpty() ? bug.getContent() : note), NoticeTarget.bug(o.getId(), bugId));
        else if (!needsAction) pushRecipients(o, by, "问题状态更新", o.getTitle() + dash(byName) + dash(bugStatusLabel(status)), NoticeTarget.bug(o.getId(), bugId));
        return voOf(o.getId());
    }

    @Override
    @Transactional
    public Map<String, Object> createBugForCustomer(Map<String, Object> p, String account, String customerName)
    {
        Long orderId = asLong(p.get("orderId"));
        JiedanOrder o = orderMapper.selectScopedForUpdate(orderId, null);
        if (!canViewProject(o, account)) return null;
        if (isBlankBug(p.get("content"), p.get("attachments"))) return null;

        Date now = new Date();
        String name = publicCustomerName(strOr(customerName, o.getCustomer()), account);
        JiedanBug bug = insertBug(orderId, name, asStr(p.get("content")), p.get("attachments"), now, "customer", null, false);

        markUnreadForCustomerAction(o, now);
        notifyNewBug(o, bug, null);
        return getForCustomer(orderId, account);
    }

    private void notifyNewBug(JiedanOrder order, JiedanBug bug, Long creatorId)
    {
        Set<Long> recipients = new LinkedHashSet<>(recipientIds(order.getOwnerId()));
        if (creatorId != null) recipients.add(creatorId);
        String title = (creatorId == null ? "客户反馈问题 #" : "新问题 #") + bug.getId() + " · " + order.getTitle();
        String summary = excerpt(strOr(bug.getContent(), "请查看问题附件"));
        String body = "问题：" + summary + "\n创建人：" + bug.getCreatedBy();
        NoticeTarget target = NoticeTarget.bug(order.getId(), bug.getId());
        for (Long recipient : recipients)
            pushService.pushToTarget(recipient, title, body, target);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> deleteBugForCustomer(Long bugId, String account)
    {
        if (StringUtils.isEmpty(account)) return null;
        JiedanOrder o = issueReviews.lockProject(bugId, null, account);
        if (o == null) return null;
        JiedanBug bug = bugMapper.lockById(bugId);
        if (bug == null || !canManageCustomerIssue(o, bug, account)) return null;
        bugUpdateMapper.deleteByBugId(bugId);
        bugMapper.deleteById(bugId);
        markUnreadForCustomerAction(o, new Date());
        clearBugReceipt(bug);
        audit.record("bug.delete", "bug", bugId, o.getTitle(), "客户删除问题：" + excerpt(bug.getContent()));
        return getForCustomer(o.getId(), account);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> addBugUpdateForCustomer(Long bugId, Map<String, Object> p, String account, String customerName)
    {
        if (StringUtils.isEmpty(account)) return null;
        JiedanOrder o = issueReviews.lockProject(bugId, null, account);
        if (o == null) return null;
        JiedanBug bug = bugMapper.lockById(bugId);
        if (bug == null) return null;
        if (isBlankBug(p.get("content"), p.get("attachments"))) return null;

        boolean reopen = Boolean.TRUE.equals(p.get("reopen"));
        Long customerId = SecurityUtils.getUserId();
        String key = Objects.toString(p.get("requestKey"), "");
        String hash = null;
        if (!key.isEmpty())
        {
            if (!key.matches("[A-Za-z0-9_-]{16,64}")) throw new ServiceException("补充凭据无效，请重新打开需求");
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("content", asStr(p.get("content")));
            fields.put("attachments", p.get("attachments"));
            fields.put("reopen", reopen);
            hash = DigestUtils.sha256Hex(JSON.toJSONString(fields, JSONWriter.Feature.SortMapEntriesByKeys));
            String previous = bugUpdateMapper.customerReplyHash(bugId, customerId, key);
            if (previous != null)
            {
                if (!previous.equals(hash)) throw new ServiceException("该次补充已保存，请使用新草稿追加其他内容");
                return getForCustomer(o.getId(), account);
            }
        }
        if (reopen)
        {
            if (!canManageCustomerIssue(o, bug, account)) return null;
            if ("ai_review".equals(bug.getStatus())) throw new ServiceException("等待内部验收",409);
            requireNoPendingReview(bug);
            if ("done".equals(o.getStatus()) || "cancelled".equals(o.getStatus()))
                throw new ServiceException("请先重新开启项目，再重新激活问题");
            if (!"action".equals(bug.getStatus())) bugMapper.updateStatus(bugId, "action");
            clearBugReceipt(bug);
        }
        Date now = new Date();
        String name = publicCustomerName(strOr(customerName, o.getCustomer()), account);
        JiedanBugUpdate reply = insertBugUpdate(bugId, name, asStr(p.get("content")), p.get("attachments"), now);
        bugUpdateMapper.insertMeta(reply.getId(), bugId, "customer", customerId, key.isEmpty() ? null : key, hash);

        markUnreadForCustomerAction(o, now);
        pushRecipients(o, null, reopen ? "客户反馈仍需调整" : "客户补充了问题说明", o.getTitle() + dash(name), NoticeTarget.bug(o.getId(), bugId));
        return getForCustomer(o.getId(), account);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> changeBugStatusForCustomer(Long bugId, Map<String, Object> p, String account, String customerName)
    {
        if (StringUtils.isEmpty(account)) return null;
        JiedanOrder o = issueReviews.lockProject(bugId, null, account);
        if (o == null) return null;
        JiedanBug bug = bugMapper.lockById(bugId);
        if (bug == null || !canManageCustomerIssue(o, bug, account)) return null;

        if ("ai_review".equals(bug.getStatus())) throw new ServiceException("等待内部验收",409);
        requireNoPendingReview(bug);
        String requested = asStr(p == null ? null : p.get("status"));
        boolean resolve = "resolved".equals(requested);
        if (!resolve && !"open".equals(requested) && !"reopen".equals(requested))
            throw new ServiceException("请选择有效的问题状态");

        // Older clients send "open" when reactivating. Both open and action are
        // shown as unresolved to customers, so a retry must preserve either state.
        boolean wasResolved = "resolved".equals(bug.getStatus());
        if (resolve == wasResolved) return getForCustomer(o.getId(), account);
        if (!resolve && ("done".equals(o.getStatus()) || "cancelled".equals(o.getStatus())))
            throw new ServiceException("请先重新开启项目，再重新激活问题");
        String status = resolve ? "resolved" : "action";
        if (resolve) orderMapper.removeTaskTurn(o.getId(),"bug-"+bugId);
        bugMapper.updateStatus(bugId, status);

        Date now = new Date();
        String name = strOr(customerName, strOr(o.getCustomer(), "客户"));
        markUnreadForCustomerAction(o, now);
        clearBugReceipt(bug);
        String title = resolve ? "客户更新了问题状态" : "客户重新激活了问题";
        String detail = resolve ? bugStatusLabel(status) : excerpt(bug.getContent());
        pushRecipients(o, null, title, o.getTitle() + dash(name) + dash(detail), NoticeTarget.bug(o.getId(), bugId));
        return getForCustomer(o.getId(), account);
    }

    private boolean closedStatus(String status)
    {
        return "done".equals(status) || "cancelled".equals(status);
    }

    private void requireNoPendingReview(JiedanBug bug)
    {
        if ("confirm".equals(bug.getStatus()))
            throw new ServiceException("这条需求正在等待客户确认，请刷新查看", 409);
    }

    private String reviewHash(Object payload)
    {
        return DigestUtils.sha256Hex(JSON.toJSONString(payload, JSONWriter.Feature.SortMapEntriesByKeys));
    }

    private Object matchingImage(JSONArray files, String url)
    {
        for (Object entry : files)
        {
            if (!(entry instanceof Map)) continue;
            Map<?, ?> file = (Map<?, ?>) entry;
            if ("image".equals(file.get("type")) && file.get("url") != null && (url.isEmpty() || url.equals(file.get("url"))))
                return file;
        }
        return null;
    }

    private Object beforeImage(JiedanBug bug, String url)
    {
        Object image = matchingImage(normalizedAttachments(bug.getAttachments(), "bug", bug.getId()), url);
        if (image != null) return image;
        for (JiedanBugUpdate reply : bugUpdateMapper.selectByBugId(bug.getId()))
        {
            if (url.isEmpty() && !"customer".equals(reply.getSenderKind())) continue;
            image = matchingImage(normalizedAttachments(reply.getAttachments(), "bugUpdate", reply.getId()), url);
            if (image != null) return image;
        }
        if (url.isEmpty()) return null;
        throw new ServiceException("请选择这条需求中的修改前截图");
    }

    private Map<String, Object> afterImage(Object entry)
    {
        if (entry == null) return null;
        if (!(entry instanceof Map)) throw new ServiceException("修改后图片无效");
        Map<?, ?> file = (Map<?, ?>) entry;
        String url = Objects.toString(file.get("url"), "");
        if (!url.matches("/jiedan/media/[a-f0-9]{32}\\.(png|jpe?g|webp|gif|bmp|avif|svg)"))
            throw new ServiceException("请上传修改后截图");
        String name = url.substring(url.lastIndexOf('/') + 1);
        Map<String, Object> image = new LinkedHashMap<>();
        image.put("type", "image");
        image.put("url", url);
        image.put("ext", name.substring(name.lastIndexOf('.') + 1));
        image.put("name", Objects.toString(file.get("name"), "修改后截图"));
        if (Boolean.TRUE.equals(file.get("annotated"))) image.put("annotated", true);
        try
        {
            java.nio.file.Path path = attachmentStorage.resolve(name);
            if (path == null) throw new ServiceException("修改后图片不存在，请重新上传");
            image.put("size", java.nio.file.Files.size(path));
        }
        catch (java.io.IOException e) { throw new ServiceException("暂时无法读取修改后图片，请重试"); }
        return image;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> requestIssueReview(Long bugId, Map<String, Object> p)
    {
        JiedanOrder order = issueReviews.lockProject(bugId, access.ownerScope(), null);
        if (order == null) throw new ServiceException(JiedanAccess.DENIED);
        JiedanBug bug = bugMapper.lockById(bugId);
        if (bug == null) throw new ServiceException("需求已删除", 409);
        Map<String,Object> delegation = taskTurn(order.getId(),"bug-"+bugId);
        if (delegation != null && "action".equals(bug.getStatus()) && !access.currentUserId().equals(asLong(delegation.get("requesterId"))))
            throw new ServiceException("请先提交给"+delegation.get("requesterName")+"确认",409);
        String key = Objects.toString(p.get("requestKey"), "");
        if (!key.matches("[A-Za-z0-9_-]{16,64}")) throw new ServiceException("请重新打开确认邀请");
        String note = Objects.toString(p.get("content"), "").trim();
        String url = Objects.toString(p.get("beforeUrl"), "").trim();
        Map<String, Object> after = afterImage(p.get("afterImage"));
        if (note.isEmpty() && after == null) throw new ServiceException("请填写修改说明或上传修改后截图");
        if (note.length() > 12000) throw new ServiceException("修改说明过长，请分段说明");
        Long previousId = asLong(p.get("previousReviewId"));
        Long me = access.currentUserId();
        String hash = reviewHash(Arrays.asList(previousId, url, after, note));
        String priorHash = issueReviews.requestHash(bugId, me, key);
        if (priorHash != null)
        {
            if (!priorHash.equals(hash)) throw new ServiceException("此次邀请已发出，请刷新后操作", 409);
            return voOf(order.getId());
        }
        if (closedStatus(order.getStatus())) throw new ServiceException("请先重新开启项目");
        if (!access.isBoss() && "pending".equals(order.getAssignState())) throw new ServiceException("请先接收项目，再请客户确认");
        if (StringUtils.isEmpty(order.getCustomerAccount())) throw new ServiceException("请先绑定客户账号");
        Long customerId = accountMapper.selectUserIdByName(order.getCustomerAccount());
        if (customerId == null) throw new ServiceException("请先绑定有效的客户账号");
        Map<String, Object> latest = issueReviews.latest(bugId);
        if (!Objects.equals(previousId, latest == null ? null : asLong(latest.get("id"))))
            throw new ServiceException("这条需求已有新的确认记录，请刷新后操作", 409);
        if ("ai_review".equals(bug.getStatus())) throw new ServiceException("请先完成内部验收",409);
        requireNoPendingReview(bug);
        Object before = beforeImage(bug, url);
        Date now = new Date();
        JiedanBugUpdate invitation = insertBugUpdate(bugId, access.currentName(), note.isEmpty() ? "请确认这次修改" : "请确认修改：" + note,
                after == null ? java.util.Collections.emptyList() : java.util.Collections.singletonList(after), now);
        bugUpdateMapper.insertMeta(invitation.getId(), bugId, "team", me, null, null);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", invitation.getId());
        row.put("bugId", bugId);
        row.put("customerId", customerId);
        row.put("senderId", me);
        row.put("key", key);
        row.put("hash", hash);
        row.put("priorStatus", strOr(bug.getStatus(), "open"));
        row.put("beforeImage", jsonStr(before));
        row.put("afterImage", jsonStr(after));
        row.put("content", note);
        issueReviews.insert(row);
        bugMapper.updateStatus(bugId, "confirm");
        clearBugReceipt(bug);
        touchOrder(order.getId(), now);
        return voOf(order.getId());
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> decideIssueReview(Long bugId, Long reviewId, Map<String, Object> p, String account)
    {
        if (StringUtils.isEmpty(account)) throw new ServiceException(JiedanAccess.DENIED);
        JiedanOrder order = issueReviews.lockProject(bugId, null, account);
        if (order == null) throw new ServiceException(JiedanAccess.DENIED);
        JiedanBug bug = bugMapper.lockById(bugId);
        Long me = SecurityUtils.getUserId();
        Map<String, Object> review = issueReviews.latest(bugId);
        if (bug == null || review == null || !reviewId.equals(asLong(review.get("id"))) || !me.equals(asLong(review.get("customerId"))))
            throw new ServiceException("此确认邀请已失效，请刷新查看", 409);
        String decision = Objects.toString(p.get("decision"), "");
        boolean accept = "accept".equals(decision);
        if (!accept && !"adjust".equals(decision)) throw new ServiceException("请选择确认结果");
        String note = Objects.toString(p.get("content"), "").trim();
        Object files = p.getOrDefault("attachments", java.util.Collections.emptyList());
        if (!(files instanceof List) || (!accept && isBlankBug(note, files)))
            throw new ServiceException("请说明还要调整的地方，或添加附件");
        String state = accept ? "accepted" : "adjustment";
        String hash = reviewHash(Arrays.asList(state, note, files));
        if (state.equals(review.get("state")) && hash.equals(review.get("decisionHash")))
            return getForCustomer(order.getId(), account);
        if (!"pending".equals(review.get("state")) || !"confirm".equals(bug.getStatus()) || closedStatus(order.getStatus()))
            throw new ServiceException("确认状态已更新，请刷新查看", 409);
        if (issueReviews.decide(reviewId, state, hash) != 1) throw new ServiceException("确认状态已更新", 409);
        Date now = new Date();
        String message = accept ? "已确认这条需求" : "还要调整：" + strOr(note, "请看附件");
        JiedanBugUpdate response = insertBugUpdate(bugId, publicCustomerName(access.currentName(), account), message, files, now);
        bugUpdateMapper.insertMeta(response.getId(), bugId, "customer", me, null, null);
        bugUpdateMapper.markSeen(me, bugId, reviewId);
        bugMapper.updateStatus(bugId, accept ? "resolved" : "action");
        clearBugReceipt(bug);
        markUnreadForCustomerAction(order, now);
        pushRecipients(order, null, accept ? "客户已确认需求" : "客户反馈仍需调整",
                order.getTitle() + "：" + excerpt(accept ? bug.getContent() : strOr(note, bug.getContent())), NoticeTarget.bug(order.getId(), bugId));
        return getForCustomer(order.getId(), account);
    }

    // ---------- 项目记事本（内部台账） ----------
    @Override
    public Map<String, Object> getNotes(Long id)
    {
        guard(id);
        JiedanOrder o = orderMapper.selectNotesById(id);
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", id);
        vo.put("notes", o == null ? "" : strOr(o.getNotes(), ""));
        vo.put("noteAttachments", o == null ? new ArrayList<>()
                : normalizedAttachments(o.getNoteAttachments(), "note", id));
        return vo;
    }

    @Override
    public Map<String, Object> saveNotes(Map<String, Object> p)
    {
        Long id = asLong(p.get("id"));
        if (id == null) return null;
        guard(id);
        JiedanOrder upd = new JiedanOrder();
        upd.setId(id);
        upd.setNotes(strOr(asStr(p.get("notes")), ""));
        upd.setNoteAttachments(jsonStr(p.get("noteAttachments")));
        upd.setUpdateTime(new Date());
        orderMapper.update(upd);
        return getNotes(id);
    }

    // ---------- 追加进度 ----------
    @Override
    public Map<String, Object> addProgress(Map<String, Object> p)
    {
        JiedanOrder o = guard(asLong(p.get("orderId")));
        Long by = access.currentUserId();
        String byName = access.currentName();
        String type = strOr(asStr(p.get("type")), "note");
        String normalized = type.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith("handoff.") || "delivery".equals(normalized))
            throw new ServiceException("交接记录由对应事项生成");
        String content = asStr(p.get("content"));
        // 内部记录可以 @ 同事：只能 @ 这个项目的负责人或总管理员（别人本来就看不到这个项目）
        List<Long> mentioned = new ArrayList<>();
        Object raw = p.get("mentions");
        if (raw instanceof List && !((List<?>) raw).isEmpty())
        {
            if (!"note".equals(type)) throw new ServiceException("只有内部记录可以 @同事");
            List<Long> allowed = collaborators(o);
            for (Object v : (List<?>) raw)
            {
                Long m = asLong(v);
                if (m == null || m.equals(by) || mentioned.contains(m)) continue;
                if (!allowed.contains(m)) throw new ServiceException("只能 @ 这个项目的负责人或总管理员");
                mentioned.add(m);
            }
        }
        Date now = new Date();
        Long timelineId = addTimeline(o.getId(), byName, type, content, jsonStr(p.get("attachments")), now);
        for (Long m : mentioned)
        {
            mentionMapper.insert(o.getId(), timelineId, m, by, byName, excerpt(content), now);
        }
        touchUnread(o, by, now);
        String text = strOr(content, "有新进度");
        NoticeTarget target = NoticeTarget.timeline(o.getId(), timelineId, type);
        for (Long m : othersThan(o.getOwnerId(), by))
        {
            if (mentioned.contains(m))
                pushService.pushToTarget(m, "💬 " + byName + " @了你", o.getTitle() + "：" + text, target);
            else
                pushService.pushToTarget(m, "💬 新动态", o.getTitle() + "：" + text + "（" + byName + "）", target);
        }
        return voOf(o.getId());
    }

    // ---------- 下一步：做什么 / 谁来做 / 哪天前 ----------
    @Override
    @Transactional
    public Map<String, Object> setNextStep(Long id, Map<String, Object> p)
    {
        JiedanOrder o = orderMapper.selectScopedForUpdate(id, access.ownerScope());
        if (o == null) throw new ServiceException(JiedanAccess.DENIED);
        Long by = access.currentUserId();
        String byName = access.currentName();
        Date now = new Date();
        String content = asStr(p.get("content"));
        content = content == null ? "" : content.trim();
        Map<String,Object> existingTurn = taskTurn(id,"next");
        Long intendedOwner = asLong(p.get("ownerId"));
        if (intendedOwner == null) intendedOwner = o.getOwnerId();
        if (existingTurn != null && !by.equals(asLong(existingTurn.get("requesterId")))
                && (content.isEmpty() || !Objects.equals(intendedOwner,pendingRecipient(o,"next"))))
            throw new ServiceException("请由发起人撤回或重新安排这一步",403);
        if (existingTurn != null && existingTurn.get("submittedAt") != null)
            throw new ServiceException("请先确认或退回已提交的下一步",409);
        if (content.isEmpty())
        {
            orderMapper.removeTaskTurn(id,"next");
            if (StringUtils.isEmpty(o.getNextStep())) return voOf(id);
            orderMapper.updateNextStep(id, null, null, null, now);
            orderMapper.clearReceipts(id, "next");
            addTimeline(id, byName, "note", byName + " 清除了下一步：" + o.getNextStep(), null, now);
            touchUnread(o, by, now);
            audit.record("order.next", "order", id, o.getTitle(), "清除下一步：" + o.getNextStep());
            return voOf(id);
        }
        applyNextStep(o, content, asLong(p.get("ownerId")), asStr(p.get("due")), by, byName, now, null);
        return voOf(id);
    }

    @Override
    @Transactional
    public Map<String, Object> completeNextStep(Long id, Map<String, Object> p)
    {
        JiedanOrder o = orderMapper.selectScopedForUpdate(id, access.ownerScope());
        if (o == null) throw new ServiceException(JiedanAccess.DENIED);
        if (StringUtils.isEmpty(o.getNextStep())) throw new ServiceException("这个项目还没有下一步");
        Long by = access.currentUserId();
        String byName = access.currentName();
        Date now = new Date();
        Map<String,Object> turn = taskTurn(id,"next");
        if (turn != null) {
            if (p != null && !Objects.toString(p.get("content"),"").trim().isEmpty())
                throw new ServiceException("请先提交这一步，发起人确认后可继续安排下一步",409);
            submitTaskTurn(o,"next",pendingRecipient(o,"next"),turn,"下一步「"+o.getNextStep()+"」",NoticeTarget.order(id),handoffSubmitNote(p));
            return voOf(id);
        }
        String done = o.getNextStep();
        addTimeline(id, byName, "note", byName + " 完成了下一步：" + done, null, now);
        audit.record("order.next.done", "order", id, o.getTitle(), "完成下一步：" + done);
        String content = asStr(p == null ? null : p.get("content"));
        content = content == null ? "" : content.trim();
        if (content.isEmpty())
        {
            orderMapper.updateNextStep(id, null, null, null, now);
            orderMapper.clearReceipts(id, "next");
            touchUnread(o, by, now);
            pushRecipients(o, by, "✅ 下一步已完成", o.getTitle() + "：" + done + "（" + byName + "）");
            return voOf(id);
        }
        // 完成后接着安排下一个
        applyNextStep(o, content, asLong(p.get("ownerId")), asStr(p.get("due")), by, byName, now, done);
        return voOf(id);
    }

    private void applyNextStep(JiedanOrder o, String content, Long nextOwner, String due, Long by, String byName, Date now, String justDone)
    {
        if (content.length() > 200) throw new ServiceException("下一步不能超过 200 字");
        if (nextOwner == null) nextOwner = o.getOwnerId();
        if (nextOwner == null || !collaborators(o).contains(nextOwner)) throw new ServiceException("下一步只能交给项目负责人或总管理员");
        due = due == null ? "" : due.trim();
        if (!due.isEmpty())
        {
            try { LocalDate.parse(due); }
            catch (Exception e) { throw new ServiceException("截止日期格式不正确"); }
        }
        if (justDone != null || StringUtils.isEmpty(o.getNextStep()) || !Objects.equals(nextOwner,pendingRecipient(o,"next"))
                || !by.equals(nextOwner) && taskTurn(o.getId(),"next") == null)
            assignTaskTurn(o.getId(),"next",nextOwner);
        orderMapper.updateNextStep(o.getId(), content, nextOwner, due.isEmpty() ? null : due, now);
        if (justDone != null || !Objects.equals(content, o.getNextStep()) || !Objects.equals(nextOwner, o.getNextOwnerId())
                || !Objects.equals(due, Objects.toString(o.getNextDue(), "")))
            orderMapper.clearReceipts(o.getId(), "next");
        String who = ownerName(nextOwner);
        String dueText = due.isEmpty() ? "" : " · " + due + " 前";
        addTimeline(o.getId(), byName, "note", byName + " 设置下一步：" + content + "（" + who + dueText + "）", null, now);
        touchUnread(o, by, now);
        audit.record("order.next", "order", o.getId(), o.getTitle(), "下一步：" + content + "；负责人：" + who + (due.isEmpty() ? "" : "；截止：" + due));
        String doneText = justDone == null ? "" : "（已完成：" + justDone + "）";
        for (Long m : othersThan(o.getOwnerId(), by))
        {
            if (m.equals(nextOwner))
                pushService.pushToMember(m, "📍 下一步交给你", o.getTitle() + "：" + content + (due.isEmpty() ? "" : "（" + due + " 前）") + " —— " + byName + doneText, o.getId());
            else if (justDone != null)
                pushService.pushToMember(m, "✅ 下一步已完成", o.getTitle() + "：" + justDone + "（" + byName + "）", o.getId());
        }
    }

    /** 能被 @、能接下一步的人：项目负责人 + 全体总管理员（账号在用的） */
    private List<Long> collaborators(JiedanOrder o)
    {
        List<Long> list = new ArrayList<>();
        for (Long m : recipientIds(o.getOwnerId()))
        {
            if (access.isAssignable(m)) list.add(m);
        }
        return list;
    }

    /** 项目换了负责人：原来交给旧负责人的下一步跟着交给新负责人（交给老板的不动） */
    private void retargetNextStep(JiedanOrder cur, Long newOwner)
    {
        orderMapper.pruneReassignedTurns(cur.getId(),newOwner);
        orderMapper.reopenReassignedTurns(cur.getId(),cur.getOwnerId());
        Long nextOwner = cur.getNextOwnerId();
        if (nextOwner != null && nextOwner.equals(cur.getOwnerId()) && newOwner != null
                && !StringUtils.isEmpty(cur.getNextStep()) && !access.bossUserIds().contains(nextOwner))
        {
            orderMapper.updateNextStep(cur.getId(), cur.getNextStep(), newOwner, cur.getNextDue(), new Date());
            nextOwner = newOwner;
        }
        Map<String,Object> turn = taskTurn(cur.getId(),"next");
        if (turn != null && Objects.equals(nextOwner == null ? newOwner : nextOwner, asLong(turn.get("requesterId"))))
            orderMapper.removeTaskTurn(cur.getId(),"next");
        orderMapper.clearReassignedReceipts(cur.getId());
    }

    // ---------- 改状态 ----------
    @Override
    @Transactional
    public Map<String, Object> changeStatus(Map<String, Object> p)
    {
        JiedanOrder o = orderMapper.selectScopedForUpdate(asLong(p.get("id")), access.ownerScope());
        if (o == null) throw new ServiceException(JiedanAccess.DENIED);
        Long by = access.currentUserId();
        String byName = access.currentName();
        String status = checkStatus(asStr(p.get("status")));
        if (p.containsKey("version") && !Objects.equals(o.getVersion(),asLong(p.get("version")))) throw new ServiceException("项目已变化，请刷新后再操作",409);
        if (!access.isBoss()) staffStatusGate(o, status);
        Date now = new Date();

        JiedanOrder upd = new JiedanOrder();
        upd.setId(o.getId());
        upd.setStatus(status);
        upd.setUpdateTime(now);
        orderMapper.update(upd);
        updateDelivery(o, o.getOwnerId(), status, p);
        if ("review".equals(status) && !"review".equals(o.getStatus()) && o.getUrgentUntil() != null
                && by.equals(o.getOwnerId())) {
            Map<String, Object> handoff = urgentHandoff(o.getId());
            if (handoff != null && !by.equals(asLong(handoff.get("requesterId")))) submitUrgency(o, handoff, null);
        }
        if (closedStatus(status)) {
            issueReviews.cancelForProject(o.getId());
            endUrgentHandoff(o.getId());
        }

        String label = STATUS_LABELS.getOrDefault(status, status);
        addTimeline(o.getId(), byName, "status", "状态更新为「" + label + "」", null, now);
        audit.record("order.status", "order", o.getId(), o.getTitle(),
                "状态 " + STATUS_LABELS.getOrDefault(o.getStatus(), strOr(o.getStatus(), "无")) + " → " + label);
        touchUnread(o, by, now);
        pushRecipients(o, by, "🔔 " + label, o.getTitle() + "（" + byName + "）");
        return voOf(o.getId());
    }

    @Override
    @Transactional
    public Map<String, Object> setHandling(Long id, Map<String, Object> payload)
    {
        // Read the latest state and check ownership under the same lock as the write.
        JiedanOrder current = orderMapper.selectScopedForUpdate(id, access.ownerScope());
        if (current == null) throw new ServiceException(JiedanAccess.DENIED);
        Object mark = payload == null ? null : payload.get("handled");
        Object expected = payload == null ? null : payload.get("version");
        if (!(mark instanceof Boolean) || !(expected instanceof Integer || expected instanceof Long)
                || ((Number) expected).longValue() < 0)
            throw new ServiceException("请选择有效的处理状态并刷新项目版本");
        if (mark.equals(current.getHandled()) && current.getHandledTime() != null) return toVO(current, true);
        if (!Objects.equals(current.getVersion(), ((Number) expected).longValue()))
            throw new ServiceException("项目已更新，请刷新后重新标记", 409);
        Map<String,Object> turn = taskTurn(id,"manual");
        if (Boolean.TRUE.equals(mark) && turn != null && !closedStatus(current.getStatus())) {
            submitTaskTurn(current,"manual",current.getOwnerId(),turn,"项目处理结果",NoticeTarget.handling(id),handoffSubmitNote(payload));
            return voOf(id);
        }
        if (Boolean.FALSE.equals(mark)) {
            assignTaskTurn(id,"manual",current.getOwnerId());
            if (current.getOwnerId()!=null && !access.currentUserId().equals(current.getOwnerId()))
                pushService.pushToTarget(current.getOwnerId(),"项目待你处理 · "+current.getTitle(),access.currentName()+" 交给你处理",NoticeTarget.handling(id));
        }
        orderMapper.updateHandling(id, (Boolean) mark, new Date());
        if ((Boolean) mark && current.getUrgentUntil() != null && !closedStatus(current.getStatus()))
        {
            Map<String, Object> handoff = urgentHandoff(id);
            if (handoff != null && access.currentUserId().equals(current.getOwnerId())
                    && !access.currentUserId().equals(asLong(handoff.get("requesterId")))) {
                if (!access.isBoss() && "pending".equals(current.getAssignState()))
                    throw new ServiceException("请先接收项目，再提交加急结果");
                submitUrgency(current, handoff, handoffSubmitNote(payload));
            } else if (handoff != null && access.currentUserId().equals(asLong(handoff.get("requesterId")))) {
                clearUrgency(id);
            }
        }
        orderMapper.clearReceipts(id, "manual");
        if (current.getUrgentUntil() != null && !mark.equals(current.getHandled())) orderMapper.clearReceipts(id, "urgent");
        return voOf(id);
    }

    @Override
    @Transactional
    public Map<String, Object> setUrgency(Long id, Map<String, Object> payload)
    {
        JiedanOrder current = orderMapper.selectScopedForUpdate(id, access.ownerScope());
        if (current == null) throw new ServiceException(JiedanAccess.DENIED);
        if (!access.isBoss() && "pending".equals(current.getAssignState()))
            throw new ServiceException("请先接收项目，再设置加急日期");
        if (!payload.containsKey("urgentUntil") || !payload.containsKey("previousUntil"))
            throw new ServiceException("请重新打开加急设置并选择日期");

        Object value = payload.get("urgentUntil");
        if (value != null)
        {
            if (!(value instanceof String) || !((String) value).matches("[1-9][0-9]{3}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}"))
                throw new ServiceException("请选择有效的加急截止时间");
            try { java.time.LocalDateTime.parse(((String) value).replace(' ', 'T')); }
            catch (java.time.format.DateTimeParseException e) { throw new ServiceException("请选择有效的加急截止时间"); }
        }
        String date = (String) value;
        boolean reopen = payload.containsKey("reopenVersion");
        boolean sameDate = Objects.equals(date, current.getUrgentUntil());
        if (reopen)
        {
            Object version = payload.get("reopenVersion");
            if (!(version instanceof Integer || version instanceof Long) || date == null
                    || "done".equals(current.getStatus()) || "cancelled".equals(current.getStatus()))
                throw new ServiceException("当前项目不能重新加入待办");
            if (sameDate && !Boolean.TRUE.equals(current.getHandled())) return toVO(current, true);
            if (!Objects.equals(current.getVersion(), ((Number) version).longValue()) || !Boolean.TRUE.equals(current.getHandled()))
                throw new ServiceException("项目处理状态已更新，请核对后重试", 409);
        }
        else if (sameDate) return toVO(current, true);
        if (!Objects.equals(payload.get("previousUntil"), current.getUrgentUntil()))
            throw new ServiceException("加急日期已被修改，请核对最新日期后重试", 409);

        Map<String, Object> handoff = urgentHandoff(id);
        if (handoff != null && handoff.get("submittedAt") != null)
            throw new ServiceException("请先处理当前加急交接，再修改截止时间", 409);
        if (reopen)
        {
            Date now = new Date();
            orderMapper.updateHandling(id, false, now);
            orderMapper.clearReceipts(id, "manual");
            addTimeline(id, access.currentName(), "note", "重新加急，已将项目标为未处理。上次标为已处理：" + strOr(current.getHandledTime(), "未记录"), null, now);
        }
        if (!sameDate) orderMapper.updateUrgentUntil(id, date);
        Long actor = access.currentUserId();
        Long requester = handoff == null ? actor : asLong(handoff.get("requesterId"));
        if (date != null && current.getOwnerId() != null && !requester.equals(current.getOwnerId())
                && !closedStatus(current.getStatus()) && (!Boolean.TRUE.equals(current.getHandled()) || handoff != null || reopen))
        {
            if (handoff == null) recordTurnChange(id,"urgent","handoff.start");
            orderMapper.saveUrgentHandoff(id, requester, date);
        } else clearUrgency(id);
        if (handoff != null && !actor.equals(requester)) {
            String text = access.currentName() + (date == null ? " 取消了本次加急" : " 将加急截止时间改为 " + date);
            addTimeline(id, access.currentName(), "handoff", text, null, new Date());
            pushService.pushToTarget(requester, "加急变更 · " + current.getTitle(), text, NoticeTarget.order(id));
        }
        orderMapper.clearReceipts(id, "urgent");
        audit.record("order.update", "order", id, current.getTitle(), date == null ? "取消加急" : "加急至 " + date);
        return voOf(id);
    }

    private void clearUrgency(Long id)
    {
        orderMapper.deleteUrgentHandoff(id);
        orderMapper.removeTaskTurn(id,"urgent");
    }

    private void endUrgentHandoff(Long id)
    {
        orderMapper.clearTaskTurns(id);
        Map<String, Object> handoff = urgentHandoff(id);
        if (handoff != null && handoff.get("submittedAt") == null) {
            clearUrgency(id);
            orderMapper.updateUrgentUntil(id, null);
            orderMapper.clearReceipts(id, "urgent");
        }
    }

    private String handoffReturnNote(Map<String,Object> body) {
        String note = strOr(asStr(body.get("note")), "").trim();
        if (note.length() > 300) throw new ServiceException("退回说明最多 300 个字");
        return note;
    }

    private String handoffSubmitNote(Map<String,Object> payload)
    {
        Object raw = payload == null ? null : payload.getOrDefault("submitNote", payload.get("note"));
        String note = strOr(asStr(raw), "").trim();
        if (note.codePointCount(0,note.length()) > 300) throw new ServiceException("说明最多 300 个字");
        return note.isEmpty() ? null : note;
    }

    private void recordTurnChange(Long id, String key, String action)
    {
        addTimeline(id, access.currentName(), action, key, null, new Date());
    }

    private void returnTurn(Long id, String key, String note)
    {
        orderMapper.returnTaskTurn(id, key, note);
        recordTurnChange(id,key,"handoff.return");
    }

    private void assignTaskTurn(Long id, String key, Long executor)
    {
        Long sender = access.currentUserId();
        if (executor != null && !executor.equals(sender)) {
            orderMapper.assignTaskTurn(id, key, sender);
            recordTurnChange(id,key,"handoff.start");
        } else orderMapper.removeTaskTurn(id, key);
    }

    private void approvalReceipt(JiedanOrder order, String key, Map<String,Object> turn, String name)
    {
        if (turn == null || turn.get("submittedBy") == null) return;
        Long submitter = asLong(turn.get("submittedBy"));
        if (submitter.equals(access.currentUserId())) return;
        Map<String,Object> data = new LinkedHashMap<>();
        data.put("orderId",order.getId());
        data.put("submitterId",submitter);
        data.put("kind",key.startsWith("bug-") ? "bug" : key);
        if (key.startsWith("bug-")) data.put("bugId", Long.valueOf(key.substring(4)));
        String label;
        switch (key) {
            case "delivery": label = "项目验收"; break;
            case "urgent": label = "加急事项"; break;
            case "next": label = "下一步「"+excerpt(order.getNextStep())+"」"; break;
            case "manual": label = "项目处理结果"; break;
            default:
                JiedanBug bug = bugMapper.selectById(asLong(data.get("bugId")));
                label = "问题 #"+data.get("bugId")+(bug == null || StringUtils.isEmpty(bug.getContent()) ? "" : "「"+excerpt(bug.getContent())+"」");
        }
        data.put("actorId",access.currentUserId());
        data.put("text",name+" 通过了你提交的"+label+" · "+excerpt(order.getTitle()));
        addTimeline(order.getId(), name, "handoff.pass", JSON.toJSONString(data), null, new Date());
    }

    private Map<Long,List<Map<String,Object>>> recentHandoffApprovals()
    {
        Map<Long,List<Map<String,Object>>> events = new LinkedHashMap<>();
        for (Map<String,Object> row : orderMapper.recentApprovals(access.currentUserId(),access.ownerScope())) {
            Map<String,Object> event = JSON.parseObject(asStr(row.remove("detail")));
            event.putAll(row);
            events.computeIfAbsent(asLong(event.get("orderId")), key -> new ArrayList<>()).add(event);
        }
        return events;
    }

    private Long projectReviewer(Long id, Map<String,Object> turn)
    {
        List<Long> reviewers = access.bossUserIds();
        Long reviewer = turn == null ? orderMapper.projectCreator(id) : asLong(turn.get("requesterId"));
        if (reviewer != null && reviewers.contains(reviewer)) return reviewer;
        return reviewers.isEmpty() ? null : reviewers.get(0);
    }

    private void updateDelivery(JiedanOrder order, Long owner, String status, Map<String,Object> payload)
    {
        if (status == null || status.equals(order.getStatus())) return;
        Long id = order.getId();
        Map<String,Object> turn = taskTurn(id,"delivery");
        Long actor = access.currentUserId();
        if ("review".equals(status) && actor.equals(owner)) {
            Long reviewer = projectReviewer(id,turn);
            if (reviewer != null && !actor.equals(reviewer)) {
                if (turn == null) recordTurnChange(id,"delivery","handoff.start");
                orderMapper.ensureTaskTurn(id, "delivery", reviewer);
                String note = handoffSubmitNote(payload);
                orderMapper.submitTaskTurn(id,"delivery",actor,note);
                addTimeline(id,access.currentName(),"delivery", note == null ? "提交项目验收" : note, jsonStr(payload.get("attachments")),new Date());
                orderMapper.clearReceipts(id,"delivery");
                return;
            }
        }
        if ("done".equals(status) && "review".equals(order.getStatus()) && turn != null) {
            if (!actor.equals(asLong(turn.get("requesterId")))) throw new ServiceException("请由本次验收人确认",403);
            approvalReceipt(order,"delivery",turn,access.currentName());
        }
        orderMapper.removeTaskTurn(id,"delivery");
        orderMapper.clearReceipts(id,"delivery");
    }

    private Map<String,Object> taskTurn(Long id, String key) {
        com.alibaba.fastjson2.JSONObject turns = JSON.parseObject(orderMapper.selectTaskTurns(id));
        return turns == null ? null : turns.getJSONObject(key);
    }

    private void submitTaskTurn(JiedanOrder order, String key, Long executor, Map<String,Object> turn, String label, NoticeTarget target, String said) {
        Long actor = access.currentUserId();
        if (!actor.equals(executor)) throw new ServiceException("请由当前处理人提交结果",403);
        if (turn.get("submittedAt") != null) throw new ServiceException("已提交，等待发起人确认",409);
        Long reviewer = asLong(turn.get("requesterId"));
        orderMapper.submitTaskTurn(order.getId(), key, actor, said);
        orderMapper.clearReceipts(order.getId(), key);
        String text = access.currentName()+" 已提交"+label+"，等"+ownerName(reviewer)+"确认"+(said == null ? "" : " · "+said);
        addTimeline(order.getId(), access.currentName(), "handoff", text, null, new Date());
        touchUnread(order, actor, new Date());
        pushService.pushToTarget(reviewer, label+"等你确认 · "+order.getTitle(), text, target);
    }

    @Override
    @Transactional
    public Map<String,Object> reviewTaskTurn(Long id, Map<String,Object> body) {
        JiedanOrder order = orderMapper.selectScopedForUpdate(id, access.ownerScope());
        if (order == null) throw new ServiceException(JiedanAccess.DENIED);
        if (!Objects.equals(order.getVersion(), asLong(body.get("version"))) || closedStatus(order.getStatus()))
            throw new ServiceException("事项已变化，请刷新后再操作",409);
        String key = asStr(body.get("kind"));
        if (!"next".equals(key) && !"manual".equals(key)) throw new ServiceException("请选择有效事项");
        Map<String,Object> turn = taskTurn(id,key);
        if (turn == null || turn.get("submittedAt")==null) throw new ServiceException("这条事项当前没有待确认的结果",409);
        Long actor = access.currentUserId();
        if (!actor.equals(asLong(turn.get("requesterId")))) throw new ServiceException("仅发起人可以确认或退回",403);
        String decision = asStr(body.get("decision"));
        boolean next = "next".equals(key);
        String label = next ? "下一步「"+order.getNextStep()+"」" : "项目处理结果";
        String note = "return".equals(decision) ? handoffReturnNote(body) : "";
        if ("confirm".equals(decision)) {
            approvalReceipt(order,key,turn,access.currentName());
            if (next) orderMapper.updateNextStep(id,null,null,null,new Date());
            else orderMapper.updateHandling(id,true,new Date());
            orderMapper.removeTaskTurn(id,key);
        } else if ("return".equals(decision)) {
            returnTurn(id,key,note);
        } else throw new ServiceException("请选择确认或退回");
        String text = access.currentName()+("confirm".equals(decision) ? " 确认完成：" : " 退回处理：")+label;
        orderMapper.clearReceipts(id,key);
        addTimeline(id,access.currentName(),"handoff",text+(note.isEmpty() ? "" : " · "+note),null,new Date());
        touchUnread(order,actor,new Date());
        pushService.pushToTarget(pendingRecipient(order,key),text,order.getTitle()+(note.isEmpty() ? "" : " · "+note),next ? NoticeTarget.order(id) : NoticeTarget.handling(id));
        return voOf(id);
    }

    private boolean activeUrgency(JiedanOrder o) {
        return o.getUrgentUntil() != null && (!Boolean.TRUE.equals(o.getHandled()) || urgentHandoff(o.getId()) != null);
    }

    private Map<String, Object> urgentHandoff(Long id)
    {
        return JSON.parseObject(orderMapper.selectUrgentHandoff(id));
    }

    // The caller holds the project row lock. Clearing the deadline removes the executor's task;
    // the handoff and its original deadline survive until the requester makes a decision.
    private void submitUrgency(JiedanOrder order, Map<String, Object> handoff, String said)
    {
        Long id = order.getId();
        Long actor = access.currentUserId();
        Long requester = asLong(handoff.get("requesterId"));
        if (handoff.get("submittedAt") != null) throw new ServiceException("这条加急已提交",409);
        orderMapper.ensureTaskTurn(id,"urgent",requester);
        orderMapper.submitTaskTurn(id,"urgent",actor,said);
        orderMapper.submitUrgentHandoff(id, actor);
        orderMapper.updateUrgentUntil(id, null);
        orderMapper.clearReceipts(id, "urgent");
        String text = access.currentName() + " 已提交加急结果，等" + ownerName(requester) + "确认" + (said == null ? "" : " · " + said);
        addTimeline(id, access.currentName(), "handoff", text, null, new Date());
        pushService.pushToTarget(requester, "加急结果等你确认 · " + order.getTitle(), text, NoticeTarget.order(id));
    }

    @Override
    @Transactional
    public Map<String, Object> reviewUrgency(Long id, Map<String, Object> payload)
    {
        JiedanOrder order = orderMapper.selectScopedForUpdate(id, access.ownerScope());
        if (order == null) throw new ServiceException(JiedanAccess.DENIED);
        Map<String, Object> handoff = urgentHandoff(id);
        if (handoff == null || handoff.get("submittedAt") == null)
            throw new ServiceException("这条加急已变更，请刷新", 409);
        Long actor = access.currentUserId();
        if (!actor.equals(asLong(handoff.get("requesterId"))))
            throw new ServiceException("仅发起这条加急的人可以确认或退回", 403);
        Object version = payload.get("version");
        if (!(version instanceof Integer || version instanceof Long)
                || !order.getVersion().equals(((Number) version).longValue()))
            throw new ServiceException("项目已更新，请刷新后确认", 409);
        String decision = asStr(payload.get("decision"));
        String text;
        if ("confirm".equals(decision)) {
            approvalReceipt(order,"urgent",handoff,access.currentName());
            orderMapper.clearReceipts(id,"urgent");
            clearUrgency(id);
            orderMapper.bumpVersion(id);
            text = "已确认本次加急完成";
        } else if ("return".equals(decision)) {
            String note = handoffReturnNote(payload);
            String due = asStr(handoff.get("due"));
            if (closedStatus(order.getStatus())) throw new ServiceException("项目已结束，请先重新开启项目再退回加急");
            if (order.getOwnerId() == null) throw new ServiceException("请先指定项目负责人");
            orderMapper.returnUrgentHandoff(id, note);
            orderMapper.removeTaskTurn(id,"urgent");
            recordTurnChange(id,"urgent","handoff.return");
            orderMapper.updateUrgentUntil(id, due);
            if (Boolean.TRUE.equals(order.getHandled())) orderMapper.updateHandling(id, false, new Date());
            orderMapper.clearReceipts(id, "urgent");
            text = "已退回加急，请继续处理，截止 " + due + (note.isEmpty() ? "" : "。说明：" + note);
        } else throw new ServiceException("请选择确认完成或退回处理");
        addTimeline(id, access.currentName(), "handoff", text, null, new Date());
        Long recipient = "confirm".equals(decision) ? asLong(handoff.get("submittedBy")) : order.getOwnerId();
        if (recipient != null && !actor.equals(recipient) && recipient.equals(order.getOwnerId()))
            pushService.pushToTarget(recipient, text, order.getTitle() + " · " + access.currentName(), NoticeTarget.order(id));
        return voOf(id);
    }

    // ---------- 标记已读（只能标记自己的） ----------
    @Override
    public Map<String, Object> markRead(Long id, Long memberId)
    {
        JiedanOrder o = guard(id);
        Long me = access.currentUserId();
        List<Long> unread = parseUnread(o.getUnread());
        // 打开项目 = 看过了这里所有 @我 的记录：对方能看到“已读”
        int seenMentions = mentionMapper.markRead(id, me, new Date());
        if (unread.remove(me))
        {
            JiedanOrder upd = new JiedanOrder();
            upd.setId(id);
            upd.setUnread(join(unread));
            orderMapper.update(upd);
        }
        else if (seenMentions > 0)
        {
            orderMapper.bumpVersion(id);
        }
        return getVersion(id);
    }

    // ---------- 改稿 +1 ----------
    @Override
    public Map<String, Object> addRevision(Map<String, Object> p)
    {
        JiedanOrder o = guard(asLong(p.get("id")));
        Long by = access.currentUserId();
        String byName = access.currentName();
        Date now = new Date();
        int n = (o.getRevisions() == null ? 0 : o.getRevisions()) + 1;
        JiedanOrder upd = new JiedanOrder();
        upd.setId(o.getId());
        upd.setRevisions(n);
        upd.setUpdateTime(now);
        orderMapper.update(upd);
        addTimeline(o.getId(), byName, "note", "客户第 " + n + " 次改稿", null, now);
        touchUnread(o, by, now);
        pushRecipients(o, by, "✏️ 客户改稿", o.getTitle() + " 第 " + n + " 次改稿");
        return voOf(o.getId());
    }

    // ---------- 催一下 ----------
    @Override
    @Transactional
    public Map<String, Object> nudge(Map<String, Object> p)
    {
        JiedanOrder o = orderMapper.selectScopedForUpdate(asLong(p.get("id")), access.ownerScope());
        if (o == null) throw new ServiceException(JiedanAccess.DENIED);
        boolean urgent = "urgent".equals(p.get("kind"));
        Long by = access.currentUserId();
        String byName = access.currentName();
        Date now = new Date();
        // 催谁：优先催负责人；若自己就是负责人则催总管理员
        Long target = o.getOwnerId();
        if (urgent)
        {
            if (target == null || target.equals(by)) throw new ServiceException("请选择其他负责人名下的项目");
            if (!activeUrgency(o)
                    || "done".equals(o.getStatus()) || "cancelled".equals(o.getStatus()))
                throw new ServiceException("这个项目当前无需加急提醒");
            if (!Objects.equals(o.getVersion(), asLong(p.get("version"))))
                throw new ServiceException("项目信息已更新，请核对后再提醒", 409);
        }
        if (target == null || target.equals(by))
        {
            target = null;
            for (Long boss : access.bossUserIds())
            {
                if (!boss.equals(by))
                {
                    target = boss;
                    break;
                }
            }
        }
        String reminder = urgent ? "请跟进「" + o.getTitle() + "」，加急截止 " + o.getUrgentUntil() + "（北京时间）" : byName + " 催了一下：" + o.getTitle();
        recordReminder(o, target, byName, reminder, now);
        if (target != null)
        {
            if (urgent) pushService.pushToMember(target, "加急提醒 · " + o.getTitle(), byName + "：" + reminder, o.getId());
            else pushService.pushToMember(target, "⏰ 催办", o.getTitle() + " —— " + byName + " 催你跟进", o.getId());
        }
        return voOf(o.getId());
    }

    private void clearBugReceipt(JiedanBug bug)
    {
        orderMapper.clearReceipts(bug.getOrderId(), "bug-" + bug.getId());
        orderMapper.clearReceipts(bug.getOrderId(), "bugs");
    }

    private Long recordReminder(JiedanOrder o, Long target, String byName, String reminder, Date now)
    {
        Long entry = addTimeline(o.getId(), byName, "note", reminder, null, now);
        // 催办标记为目标未读
        if (target != null)
        {
            List<Long> unread = parseUnread(o.getUnread());
            if (!unread.contains(target)) unread.add(target);
            JiedanOrder upd = new JiedanOrder();
            upd.setId(o.getId());
            upd.setUnread(join(unread));
            upd.setUpdateTime(now);
            orderMapper.update(upd);
        }
        return entry;
    }

    private Long pendingRecipient(JiedanOrder order, String kind)
    {
        if ("next".equals(kind) && order.getNextOwnerId() != null) return order.getNextOwnerId();
        return order.getOwnerId();
    }

    /** Complete the specific task displayed in the notification drawer, under the same lock as its version check. */
    @Override
    @Transactional
    public Map<String, Object> completePendingItem(Long id, Map<String, Object> payload)
    {
        JiedanOrder order = orderMapper.selectScopedForUpdate(id, access.ownerScope());
        if (order == null) throw new ServiceException(JiedanAccess.DENIED);
        Object version = payload.get("version");
        if (!(version instanceof Integer || version instanceof Long)
                || !Objects.equals(order.getVersion(), ((Number) version).longValue()))
            throw new ServiceException("项目已更新，请核对最新待处理事项后再标记", 409);
        if ("done".equals(order.getStatus()) || "cancelled".equals(order.getStatus()))
            throw new ServiceException("项目已结束，请刷新待处理清单", 409);
        String kind = Objects.toString(payload.get("kind"), "");
        Long recipient = pendingRecipient(order, kind);
        if (recipient == null || !recipient.equals(access.currentUserId()))
            throw new ServiceException("仅当前事项的负责人可以在通知里标记已处理", 403);
        switch (kind) {
            case "bug":
                Object bugId = payload.get("bugId");
                if (!(bugId instanceof Integer || bugId instanceof Long)) break;
                Long target = ((Number) bugId).longValue();
                if (bugMapper.selectActionForReminder(id, target).isEmpty()) break;
                Map<String,Object> change = new LinkedHashMap<>(payload);
                change.put("status","resolved");
                return changeBugStatus(target,change);
            case "next":
                if (StringUtils.isEmpty(order.getNextStep())) break;
                return completeNextStep(id, payload);
            case "urgent":
                if (!activeUrgency(order)) break;
                if (!access.isBoss() && "pending".equals(order.getAssignState()))
                    throw new ServiceException("请先接收项目，再提交加急结果");
                Map<String, Object> handoff = urgentHandoff(id);
                if (handoff != null && !recipient.equals(asLong(handoff.get("requesterId")))) {
                    submitUrgency(order, handoff, handoffSubmitNote(payload));
                    return voOf(id);
                }
                Map<String, Object> urgency = new LinkedHashMap<>();
                urgency.put("previousUntil", order.getUrgentUntil());
                urgency.put("urgentUntil", null);
                return setUrgency(id, urgency);
            case "manual":
                if (order.getHandledTime() == null || Boolean.TRUE.equals(order.getHandled())) break;
                Map<String,Object> mark = new LinkedHashMap<>(payload);
                mark.put("handled",true);
                return setHandling(id, mark);
            default:
                throw new ServiceException("请选择有效的待处理事项");
        }
        throw new ServiceException("这条事项已变更，请核对最新待处理清单", 409);
    }

    private String changeKey(String kind, Map<String,Object> payload)
    {
        if ("bug".equals(kind)) {
            Long bugId = asLong(payload.get("bugId"));
            if (bugId == null || bugId <= 0) throw new ServiceException("请选择具体问题");
            return "bug-"+bugId;
        }
        if (List.of("next","manual","urgent","delivery").contains(kind)) return kind;
        throw new ServiceException("请选择有效的交接事项");
    }

    @Override
    @Transactional
    public Map<String,Object> withdrawHandoff(Long id, Map<String,Object> payload)
    {
        JiedanOrder order = orderMapper.selectScopedForUpdate(id, access.ownerScope());
        if (order == null) throw new ServiceException(JiedanAccess.DENIED);
        if (closedStatus(order.getStatus()) || !Objects.equals(order.getVersion(),asLong(payload.get("version"))))
            throw new ServiceException("事项已变化，请刷新后再操作",409);
        String kind = Objects.toString(payload.get("kind"),"");
        String key = changeKey(kind,payload);
        Map<String,Object> turn = "urgent".equals(kind) ? urgentHandoff(id) : taskTurn(id,key);
        Long me = access.currentUserId();
        if (turn == null || turn.get("submittedAt") == null) throw new ServiceException("当前没有可以撤回的提交",409);
        if (!me.equals(asLong(turn.get("submittedBy"))) || !me.equals(pendingRecipient(order,kind)))
            throw new ServiceException("只有当前事项的提交人可以撤回",403);
        if ("bug".equals(kind)) {
            JiedanBug bug = bugMapper.lockById(asLong(payload.get("bugId")));
            if (bug == null || !id.equals(bug.getOrderId()) || !"ai_review".equals(bug.getStatus()))
                throw new ServiceException("问题已变化，请刷新后再操作",409);
            bugMapper.updateStatus(bug.getId(),"action");
        } else if ("urgent".equals(kind)) {
            orderMapper.withdrawUrgentTurn(id);
            orderMapper.updateUrgentUntil(id,asStr(turn.get("due")));
            if (Boolean.TRUE.equals(order.getHandled())) orderMapper.updateHandling(id,false,new Date());
        } else if ("delivery".equals(kind)) {
            if (!"review".equals(order.getStatus())) throw new ServiceException("项目当前没有待验收的提交",409);
            JiedanOrder update = new JiedanOrder();
            update.setId(id);
            update.setStatus("doing");
            orderMapper.update(update);
        }
        orderMapper.withdrawTaskTurn(id,key);
        orderMapper.clearReceipts(id,key);
        String label = "bug".equals(kind) ? "问题 #"+payload.get("bugId") :
                "delivery".equals(kind) ? "项目验收" : "urgent".equals(kind) ? "加急事项" : "next".equals(kind) ? "下一步" : "项目处理结果";
        addTimeline(id,access.currentName(),"handoff",access.currentName()+" 撤回了"+label+"的提交",null,new Date());
        touchUnread(order,me,new Date());
        return voOf(id);
    }

    @Override
    public void remindPendingItem(Long id, Map<String, Object> payload)
    {
        Runnable deliver = transactions.execute(status -> preparePendingReminder(id, payload));
        deliver.run();
    }

    private Runnable preparePendingReminder(Long id, Map<String, Object> payload)
    {
        JiedanOrder order = orderMapper.selectScopedForUpdate(id, access.ownerScope());
        if (order == null) throw new ServiceException(JiedanAccess.DENIED);
        if (closedStatus(order.getStatus()) && !"urgentReview".equals(payload.get("kind")))
            throw new ServiceException("项目已结束，无需提醒");
        Object version = payload.get("version");
        if (!(version instanceof Integer || version instanceof Long)
                || !Objects.equals(order.getVersion(), ((Number) version).longValue()))
            throw new ServiceException("项目已更新，请核对最新待处理事项后再提醒", 409);

        String taskKey = Objects.toString(payload.get("kind"), "");
        boolean review = taskKey.endsWith("Review");
        if (review) {
            String type = taskKey.substring(0,taskKey.length()-"Review".length());
            taskKey = changeKey("issue".equals(type) ? "bug" : type,payload);
        }
        Map<String,Object> turn = "urgent".equals(taskKey) ? urgentHandoff(id) : taskTurn(id,taskKey);
        Long recipient = pendingRecipient(order, taskKey);
        NoticeTarget target = NoticeTarget.order(id);
        String detail;
        if (review) {
            if (turn == null || turn.get("submittedAt") == null)
                throw new ServiceException("这条事项当前没有待确认的提交",409);
            if (!access.currentUserId().equals(asLong(turn.get("submittedBy"))))
                throw new ServiceException("请由本次提交人提醒验收",403);
            recipient = asLong(turn.get("requesterId"));
            detail = "请确认已提交的"+("delivery".equals(taskKey) ? "项目验收" : "next".equals(taskKey) ? "下一步" :
                    "urgent".equals(taskKey) ? "加急事项" : "manual".equals(taskKey) ? "项目处理结果" : "问题 #"+payload.get("bugId"));
            if (taskKey.startsWith("bug-")) target = NoticeTarget.bug(id,asLong(payload.get("bugId")));
            else if ("manual".equals(taskKey)) target = NoticeTarget.handling(id);
        } else {
            if (turn != null && turn.get("submittedAt") != null)
                throw new ServiceException("处理人已提交，等发起人确认",409);
            switch (taskKey) {
            case "delivery":
                if (!"doing".equals(order.getStatus()) || turn == null)
                    throw new ServiceException("这条项目当前无需重新提交验收",409);
                detail = "请处理项目并提交验收"+(turn.get("returnNote") == null ? "" : " · "+turn.get("returnNote"));
                break;
            case "urgent":
                if (!activeUrgency(order))
                    throw new ServiceException("这条加急事项已经移出待处理");
                detail = "加急截止 " + order.getUrgentUntil() + "（北京时间）";
                break;
            case "manual":
                if (Boolean.TRUE.equals(order.getHandled()) || order.getHandledTime() == null)
                    throw new ServiceException("这个项目当前没有手动标记为未处理");
                detail = "项目已标为未处理，请跟进并更新处理状态";
                target = NoticeTarget.handling(id);
                break;
            case "next":
                if (order.getNextStep() == null || order.getNextStep().trim().isEmpty())
                    throw new ServiceException("这条下一步已经移出待处理");
                detail = "下一步：" + order.getNextStep()
                        + (order.getNextDue() == null ? "" : " · 截止 " + order.getNextDue());
                break;
            case "bug":
                Object bugId = payload.get("bugId");
                if (bugId != null && (!(bugId instanceof Integer || bugId instanceof Long) || ((Number) bugId).longValue() <= 0))
                    throw new ServiceException("问题编号无效，请刷新后重试");
                List<JiedanBug> bugs = bugMapper.selectActionForReminder(id, bugId == null ? null : ((Number) bugId).longValue());
                if (bugs.isEmpty()) throw new ServiceException("这个问题已移出待处理，请核对最新列表");
                if (bugs.size() == 1) {
                    JiedanBug bug = bugs.get(0);
                    detail = "问题 #" + bug.getId() + "：" + excerpt(strOr(bug.getContent(), "请查看问题附件"));
                    target = NoticeTarget.bug(id, bug.getId());
                    taskKey = "bug-" + bug.getId();
                } else {
                    detail = "请跟进项目中标记为需处理的问题";
                    target = NoticeTarget.pendingBugs(id);
                    taskKey = "bugs";
                }
                break;
            default:
                throw new ServiceException("请选择有效的待处理事项");
            }
        }
        if (recipient == null || !collaborators(order).contains(recipient))
            throw new ServiceException("请先为这条事项指定有效的负责人");
        String sender = access.currentName();
        Runnable deliver = pushService.prepareWecomReminder(recipient, "待处理提醒 · " + order.getTitle(), sender + " 提醒你：" + detail, target);
        Long entry = recordReminder(order, recipient, sender, "向" + ownerName(recipient) + "发起微信提醒：" + detail, new Date());
        String key = taskKey;
        orderMapper.beginReceipt(id, key, recipient, entry);
        return () -> {
            deliver.run();
            try {
                transactions.executeWithoutResult(status -> {
                    // Lock the project first, as when reserving the send. A changed task discards a late acknowledgement.
                    orderMapper.bumpVersion(id);
                    orderMapper.confirmReceipt(id, key, entry);
                });
            } catch (org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException e) {
                throw new ServiceException("企业微信已接收，但提醒记录暂未保存，请勿重复发送");
            }
        };
    }

    // ---------- 推送设置 ----------
    @Override
    public Map<String, String> getPushConfig()
    {
        boolean boss = access.isBoss();
        String mine = sendKeyOf(access.currentUserId());
        String myWecom = WECOM_USER + access.currentUserId();
        Map<String, String> cfg = new LinkedHashMap<>();
        for (JiedanSetting s : settingMapper.selectAll())
        {
            String key = s.getSettingKey();
            String value = s.getSettingValue();
            if (!boss)
            {
                // 下属管理员看不到别人的 SendKey / 企业微信账号，也看不到企业微信应用的配置
                if (key.startsWith("push.sendkey.") && !key.equals(mine)) continue;
                if (key.startsWith("push.wecom.") && !key.equals(myWecom)) continue;
            }
            // Secret 和 EncodingAESKey 只告诉“已填”，不回传原文
            if (SECRET_KEYS.contains(key) && value != null && !value.isEmpty()) value = MASK;
            cfg.put(key, value);
        }
        if (!boss && !cfg.containsKey(mine)) cfg.put(mine, "");
        if (!boss && !cfg.containsKey(myWecom)) cfg.put(myWecom, "");
        return cfg;
    }

    private static final String WECOM_USER = "push.wecom.user.";
    private static final String MASK = "********";
    private static final List<String> SECRET_KEYS = Arrays.asList("push.wecom.secret", "push.wecom.aesKey");

    @Override
    public void savePushConfig(Map<String, Object> p)
    {
        boolean boss = access.isBoss();
        String mine = sendKeyOf(access.currentUserId());
        String myWecom = WECOM_USER + access.currentUserId();
        for (Map.Entry<String, Object> e : p.entrySet())
        {
            if (e.getKey() == null || !e.getKey().startsWith("push.")) continue;
            if (!boss && !e.getKey().equals(mine) && !e.getKey().equals(myWecom)) throw new ServiceException("无权修改该推送设置");
            String v = e.getValue() == null ? "" : e.getValue().toString().trim();
            // 页面上显示的是遮住的 Secret：原样传回来表示“不改”
            if (SECRET_KEYS.contains(e.getKey()) && MASK.equals(v)) continue;
            checkWecomSetting(e.getKey(), v);
            if ("push.siteUrl".equals(e.getKey()))
            {
                // 推送里“打开项目”链接的前缀，例如 https://jiedanxt.oksja.cn
                if (!v.isEmpty() && !v.matches("https?://[^\\s]+")) throw new ServiceException("系统网址需以 http:// 或 https:// 开头");
                v = v.replaceAll("/+$", "");
            }
            if ("push.stallDays".equals(e.getKey()))
            {
                int n;
                try { n = Integer.parseInt(v); } catch (Exception ex) { throw new ServiceException("停滞提醒天数需为 1-60 的整数"); }
                if (n < 1 || n > 60) throw new ServiceException("停滞提醒天数需为 1-60 的整数");
            }
            settingMapper.upsert(e.getKey(), v);
        }
    }

    /** 企业微信几项设置的格式校验（填错了推送会一直失败，不如保存时就说清楚） */
    private void checkWecomSetting(String key, String v)
    {
        if (v.isEmpty() || !key.startsWith("push.wecom.")) return;
        if ("push.wecom.corpId".equals(key) && !v.matches("[A-Za-z0-9]{6,64}")) throw new ServiceException("企业 ID 格式不对（一般是 ww 开头的一串字母数字）");
        if ("push.wecom.agentId".equals(key) && !v.matches("\\d{1,12}")) throw new ServiceException("AgentId 应该是一串数字");
        if ("push.wecom.secret".equals(key) && !v.matches("[A-Za-z0-9_\\-]{8,128}")) throw new ServiceException("Secret 格式不对，请从应用详情里重新复制");
        if ("push.wecom.token".equals(key) && !v.matches("[A-Za-z0-9]{1,32}")) throw new ServiceException("Token 只能是 32 位以内的字母和数字");
        if ("push.wecom.aesKey".equals(key) && !v.matches("[A-Za-z0-9]{43}")) throw new ServiceException("EncodingAESKey 应该是 43 位字母和数字");
        if ((key.startsWith(WECOM_USER) || key.startsWith("push.wecom.quoteCopy.")) && "@all".equalsIgnoreCase(v)) throw new ServiceException("请选择个人企业微信账号");
        if ((key.startsWith(WECOM_USER) || key.startsWith("push.wecom.quoteCopy.")) && !v.matches("[A-Za-z0-9_.@\\-]{1,64}")) throw new ServiceException("企业微信账号格式不对（通讯录里成员详情的「账号」）");
    }

    @Override
    public String testPush(Long memberId)
    {
        Long target = access.isBoss() ? memberId : access.currentUserId();
        return pushService.testPush(target);
    }

    // ---------- 定时：逾期/临近截止提醒 ----------
    @Override
    public int runDeadlineReminder()
    {
        int aheadDays = 1;
        try { aheadDays = Integer.parseInt(strOr(settingMapper.selectValue("push.aheadDays"), "1")); } catch (Exception ignore) {}
        LocalDate today = LocalDate.now();
        String todayStr = today.toString();
        String limitStr = today.plusDays(aheadDays).toString();
        int count = 0;
        for (JiedanOrder o : orderMapper.selectAll())
        {
            String st = o.getStatus();
            String dl = o.getDeadline();
            if (dl == null || dl.isEmpty()) continue;
            if (!Arrays.asList("pending", "doing", "review").contains(st)) continue;
            if (o.getOwnerId() == null) continue;
            String tip;
            if (dl.compareTo(todayStr) < 0) tip = "已逾期";
            else if (dl.compareTo(limitStr) <= 0) tip = (dl.equals(todayStr) ? "今天到期" : "即将到期");
            else continue;
            pushService.pushToMember(o.getOwnerId(),
                    "⏰ 截止提醒 · " + tip,
                    o.getTitle() + dash(o.getCustomer()) + " 截止 " + dl, o.getId());
            count++;
        }
        return count;
    }

    // ================= 收款流水 =================
    private static final BigDecimal PAYMENT_MAX = new BigDecimal("99999999.99");

    /** 记一笔收款。已收金额、是否收清都由流水合计推算；订单的版本号随之 +1，其他设备会自动刷新。 */
    @Override
    @Transactional
    public Map<String, Object> addPayment(Long orderId, Map<String, Object> p)
    {
        if (!access.isBoss()) throw new ServiceException("收款要先提交申请，确认后才入账");
        return addPaymentAs(orderId, p, null);
    }

    /** 记收款；createdBy 为空表示当前登录人（老板批准员工的收款申请时，记在申请人名下）。 */
    @Override
    @Transactional
    public Map<String, Object> addPaymentAs(Long orderId, Map<String, Object> p, String createdBy)
    {
        JiedanOrder o = guard(orderId);
        PaymentInput in = parsePayment(p);
        BigDecimal amount = in.amount;
        LocalDate day = in.day;
        String note = in.note;

        Date now = new Date();
        JiedanPayment pay = new JiedanPayment();
        pay.setOrderId(orderId);
        pay.setAmount(amount);
        pay.setPayDate(day.toString());
        pay.setNote(note);
        pay.setCreatedBy(strOr(createdBy, access.currentName()));
        pay.setCreateTime(now);
        paymentMapper.insert(pay);

        JiedanOrder after = syncReceived(o, now);
        audit.record("payment.add", "order", orderId, o.getTitle(),
                "收款 " + money(amount) + "（" + day + (note.isEmpty() ? "" : "，" + note) + "）；"
                        + "累计已收 " + money(after.getReceivedAmount()) + " / 报价 " + money(o.getAmount()));
        return voOf(orderId);
    }

    /** 删掉记错的一笔收款（留痕）。 */
    @Override
    @Transactional
    public Map<String, Object> deletePayment(Long paymentId)
    {
        if (!access.isBoss()) throw new ServiceException("当前账号无权删除收款记录");
        JiedanPayment pay = paymentId == null ? null : paymentMapper.selectById(paymentId);
        if (pay == null) throw new ServiceException("这笔收款不存在");
        JiedanOrder o = guard(pay.getOrderId());
        paymentMapper.deleteById(paymentId);
        JiedanOrder after = syncReceived(o, new Date());
        audit.record("payment.delete", "order", o.getId(), o.getTitle(),
                "删除一笔收款 " + money(pay.getAmount()) + "（" + pay.getPayDate() + "）；累计已收 "
                        + money(after.getReceivedAmount()) + " / 报价 " + money(o.getAmount()));
        return voOf(o.getId());
    }

    /** 实收统计（只含我能看到的项目）：本月、累计、最近 6 个月逐月。 */
    @Override
    public Map<String, Object> income()
    {
        Long scope = access.ownerScope();
        YearMonth thisMonth = YearMonth.now();
        YearMonth first = thisMonth.minusMonths(5);
        Map<String, BigDecimal> byMonth = new LinkedHashMap<>();
        for (int i = 0; i < 6; i++) byMonth.put(first.plusMonths(i).toString(), BigDecimal.ZERO);
        for (Map<String, Object> row : paymentMapper.selectMonthlyIncome(scope, first.atDay(1).toString()))
        {
            String m = asStr(row.get("month"));
            if (m != null && byMonth.containsKey(m)) byMonth.put(m, asDecimal(row.get("amount")));
        }
        List<Map<String, Object>> months = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : byMonth.entrySet())
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("month", e.getKey());
            m.put("amount", e.getValue());
            months.add(m);
        }
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("thisMonth", byMonth.get(thisMonth.toString()));
        vo.put("total", paymentMapper.selectTotalIncome(scope));
        vo.put("months", months);
        return vo;
    }

    private static class PaymentInput
    {
        BigDecimal amount;
        LocalDate day;
        String note;
    }

    private PaymentInput parsePayment(Map<String, Object> p)
    {
        PaymentInput in = new PaymentInput();
        in.amount = parseMoney(p.get("amount"));
        if (in.amount.signum() <= 0) throw new ServiceException("收款金额要大于 0");
        if (in.amount.compareTo(PAYMENT_MAX) > 0) throw new ServiceException("收款金额太大，请检查是否多输了几位");

        String payDate = strOr(asStr(p.get("payDate")), LocalDate.now().toString()).trim();
        try { in.day = LocalDate.parse(payDate); }
        catch (Exception e) { throw new ServiceException("收款日期格式不对，应为 年-月-日"); }
        if (in.day.isAfter(LocalDate.now().plusDays(1))) throw new ServiceException("收款日期不能是将来的日期");

        in.note = strOr(asStr(p.get("note")), "").trim();
        if (in.note.length() > 200) throw new ServiceException("备注最多 200 个字");
        return in;
    }

    /** 校验并整理一笔收款的内容（金额 / 日期 / 备注），员工提交收款申请时用；通过后得到可以存起来的规范写法。 */
    @Override
    public Map<String, Object> normalizePaymentInput(Map<String, Object> p)
    {
        PaymentInput in = parsePayment(p);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("amount", in.amount.toPlainString());
        out.put("payDate", in.day.toString());
        out.put("note", in.note);
        return out;
    }

    /** 按流水重新算订单的已收金额和是否收清。 */
    private JiedanOrder syncReceived(JiedanOrder o, Date now)
    {
        BigDecimal sum = paymentMapper.sumByOrderId(o.getId());
        JiedanOrder upd = new JiedanOrder();
        upd.setId(o.getId());
        upd.setReceivedAmount(sum);
        upd.setPaid(isFullyPaid(sum, o.getAmount()) ? 1 : 0);
        upd.setUpdateTime(now);
        orderMapper.update(upd);
        return upd;
    }

    /** 已收清 = 收到过钱，并且不少于报价。报价为 0（还没报价）收到钱也算收清。 */
    private boolean isFullyPaid(BigDecimal received, BigDecimal amount)
    {
        BigDecimal r = received == null ? BigDecimal.ZERO : received;
        BigDecimal a = amount == null ? BigDecimal.ZERO : amount;
        return r.signum() > 0 && r.compareTo(a) >= 0;
    }

    private BigDecimal parseMoney(Object o)
    {
        if (o == null) throw new ServiceException("请填写收款金额");
        try
        {
            return new BigDecimal(o.toString().trim()).setScale(2, RoundingMode.HALF_UP);
        }
        catch (Exception e)
        {
            throw new ServiceException("收款金额格式不对");
        }
    }

    private String checkStatus(String status)
    {
        if (status == null || !STATUS_LABELS.containsKey(status)) throw new ServiceException("项目状态不正确");
        return status;
    }

    // ================= 流转：接收 / 拒绝 / 交回 / 退回返工 =================
    private String requireReason(String reason)
    {
        String why = strOr(reason, "").trim();
        if (why.isEmpty()) throw new ServiceException("请写一下原因");
        if (why.length() > 500) throw new ServiceException("原因最多 500 个字");
        return why;
    }

    /** 员工必须先接收老板派来的项目，才能继续操作。 */
    private void requireAccepted(JiedanOrder o)
    {
        if ("pending".equals(o.getAssignState()))
        {
            throw new ServiceException("项目已分配给你，请先点“接收”（或“拒绝”）再继续");
        }
    }

    /** 员工改状态的规则：要先接收；不能自己标“已完成”（走“提交验收”）、不能自己取消（走申请）。 */
    private void staffStatusGate(JiedanOrder o, String status)
    {
        requireAccepted(o);
        if ("done".equals(status)) throw new ServiceException("请点“提交验收”，验收通过后才算完成");
        if ("cancelled".equals(status)) throw new ServiceException("取消项目要先提交申请，审批通过后才生效");
    }

    /** 员工接收老板派来的项目。 */
    @Override
    @Transactional
    public Map<String, Object> accept(Long id)
    {
        JiedanOrder o = guard(id);
        if (access.isBoss() || !"pending".equals(o.getAssignState())) throw new ServiceException("这个项目不需要接收");
        Long me = access.currentUserId();
        String name = access.currentName();
        Date now = new Date();
        JiedanOrder upd = new JiedanOrder();
        upd.setId(id);
        upd.setAssignState("");
        upd.setUpdateTime(now);
        orderMapper.update(upd);
        addTimeline(id, name, "note", name + " 接收了这个项目", null, now);
        touchUnread(o, me, now);
        audit.record("order.accept", "order", id, o.getTitle(), "接收了项目");
        pushRecipients(o, me, "✅ 已接收", o.getTitle() + "（" + name + "）");
        return voOf(id);
    }

    /** 员工拒绝刚派来的项目：回到老板名下，必须写原因。 */
    @Override
    @Transactional
    public Map<String, Object> reject(Long id, String reason)
    {
        JiedanOrder o = guard(id);
        if (access.isBoss() || !"pending".equals(o.getAssignState()))
        {
            throw new ServiceException("只有刚派给你、还没接收的项目才能拒绝");
        }
        return giveBack(o, reason, "order.reject", "拒绝了项目", "🙅 已拒绝");
    }

    /** 员工把做到一半的项目交回老板：回到老板名下并变成“待确认”，必须写原因。 */
    @Override
    @Transactional
    public Map<String, Object> handback(Long id, String reason)
    {
        JiedanOrder o = guard(id);
        if (access.isBoss()) throw new ServiceException("这是你自己名下的项目，不需要交回");
        if ("done".equals(o.getStatus()) || "cancelled".equals(o.getStatus()))
        {
            throw new ServiceException("已完成或已取消的项目不能交回");
        }
        return giveBack(o, reason, "order.handback", "交回项目", "↩️ 已交回");
    }

    private Map<String, Object> giveBack(JiedanOrder o, String reason, String action, String verb, String pushTitle)
    {
        String why = requireReason(reason);
        List<Long> bosses = access.bossUserIds();
        if (bosses.isEmpty()) throw new ServiceException("未找到接收账号，请联系项目发起人");
        Long boss = bosses.get(0);
        Long me = access.currentUserId();
        String name = access.currentName();
        Date now = new Date();

        JiedanOrder upd = new JiedanOrder();
        upd.setId(o.getId());
        upd.setOwnerId(boss);
        upd.setStatus("pending");
        upd.setAssignState("");
        upd.setUnread(join(othersThan(boss, me)));
        upd.setUpdateTime(now);
        orderMapper.update(upd);
        retargetNextStep(o, boss);

        addTimeline(o.getId(), name, "note", name + " " + verb + "：" + why, null, now);
        audit.record(action, "order", o.getId(), o.getTitle(), "原因：" + why);
        for (Long m : othersThan(boss, me))
        {
            pushService.pushToMember(m, pushTitle, o.getTitle() + "（" + name + "）：" + why, o.getId());
        }
        // 交回之后员工就看不到这个项目了，只回一个最小的结果
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", o.getId());
        vo.put("ownerId", boss);
        vo.put("status", "pending");
        return vo;
    }

    /** 老板验收不通过：退回给负责人返工（项目回到“进行中”，改稿次数 +1），必须写原因。 */
    @Override
    @Transactional
    public Map<String, Object> returnForRework(Long id, Map<String,Object> payload)
    {
        access.requireBoss();
        JiedanOrder o = orderMapper.selectScopedForUpdate(id,access.ownerScope());
        if (o == null) throw new ServiceException(JiedanAccess.DENIED);
        if (payload.containsKey("version") && !Objects.equals(o.getVersion(),asLong(payload.get("version"))))
            throw new ServiceException("项目已变化，请刷新后再操作",409);
        if (!"review".equals(o.getStatus()) && !"done".equals(o.getStatus()))
        {
            throw new ServiceException("只有“待验收”或“已完成”的项目可以退回返工");
        }
        String why = requireReason(asStr(payload.get("reason")));
        Long me = access.currentUserId();
        String name = access.currentName();
        Date now = new Date();

        JiedanOrder upd = new JiedanOrder();
        upd.setId(id);
        upd.setStatus("doing");
        upd.setRevisions((o.getRevisions() == null ? 0 : o.getRevisions()) + 1);
        upd.setUnread(join(othersThan(o.getOwnerId(), me)));
        upd.setUpdateTime(now);
        orderMapper.update(upd);
        // 退回原因记在「项目验收」这条交接上：负责人的待办里直接看到为什么被退回
        if (o.getOwnerId() != null && !o.getOwnerId().equals(me)) {
            Map<String,Object> delivery = taskTurn(id,"delivery");
            if (delivery != null && delivery.get("submittedAt") != null && !me.equals(asLong(delivery.get("requesterId"))))
                throw new ServiceException("请由本次验收人退回",403);
            if (delivery == null) recordTurnChange(id,"delivery","handoff.start");
            orderMapper.ensureTaskTurn(id, "delivery", me);
            String reasonText = why.codePointCount(0,why.length()) <= 300 ? why : why.substring(0,why.offsetByCodePoints(0,300));
            returnTurn(id, "delivery", reasonText);
            orderMapper.clearReceipts(id,"delivery");
        } else orderMapper.removeTaskTurn(id, "delivery");

        addTimeline(id, name, "note", name + " 退回返工：" + why, null, now);
        audit.record("order.return", "order", id, o.getTitle(), "原因：" + why + "；改稿次数 +1");
        pushRecipients(o, me, "🔁 退回返工", o.getTitle() + "：" + why);
        return voOf(id);
    }

    /**
     * 给申请流程用：在项目时间线里记一笔、把项目标成相关人未读并推送。
     * 项目已在回收站里也照样记（比如删除申请被批准之后）。
     */
    @Override
    public void flowEvent(Long orderId, String text, String pushTitle, String pushBody)
    {
        JiedanOrder o = orderMapper.selectByIdAny(orderId);
        if (o == null) return;
        Long by = access.currentUserId();
        Date now = new Date();
        addTimeline(orderId, access.currentName(), "note", text, null, now);
        touchUnread(o, by, now);
        if (pushTitle != null) pushRecipients(o, by, pushTitle, pushBody);
    }

    // ================= 内部辅助 =================
    /** 取项目并校验当前登录人有权访问；不存在与无权访问返回同样的提示，避免泄露项目是否存在。 */
    private JiedanOrder guard(Long id)
    {
        JiedanOrder o = id == null ? null : orderMapper.selectById(id);
        if (o == null || !access.canAccess(o)) throw new ServiceException(JiedanAccess.DENIED);
        return o;
    }

    /** 内部取详情（调用方已校验过权限）。 */
    private Map<String, Object> voOf(Long id)
    {
        JiedanOrder o = orderMapper.selectById(id);
        return o == null ? null : toVO(o, true);
    }

    /** 项目相关人：负责人 + 全体总管理员（去重）。 */
    private List<Long> recipientIds(Long ownerId)
    {
        Set<Long> set = new LinkedHashSet<>();
        if (ownerId != null) set.add(ownerId);
        set.addAll(access.bossUserIds());
        return new ArrayList<>(set);
    }

    /** 相关人里除去操作人自己（by 为空表示操作人不是后台成员，全员保留）。 */
    private List<Long> othersThan(Long ownerId, Long by)
    {
        List<Long> list = recipientIds(ownerId);
        if (by != null) list.remove(by);
        return list;
    }

    private void pushRecipients(JiedanOrder o, Long by, String title, String content)
    {
        pushRecipients(o, by, title, content, NoticeTarget.order(o.getId()));
    }

    private void pushRecipients(JiedanOrder o, Long by, String title, String content, NoticeTarget target)
    {
        for (Long m : othersThan(o.getOwnerId(), by))
        {
            pushService.pushToTarget(m, title, content, target);
        }
    }

    /** 客户有动作：把项目的相关人全部置为未读。 */
    private void markUnreadForCustomerAction(JiedanOrder o, Date now)
    {
        JiedanOrder upd = new JiedanOrder();
        upd.setId(o.getId());
        upd.setUnread(join(recipientIds(o.getOwnerId())));
        upd.setUpdateTime(now);
        orderMapper.update(upd);
    }

    private String sendKeyOf(Long userId)
    {
        return "push.sendkey." + userId;
    }

    /** 操作日志里显示的人名：优先账号昵称，找不到就退回“用户#id”。 */
    private String ownerName(Long userId)
    {
        if (userId == null) return "无";
        String name = accountMapper.selectNickName(userId);
        return StringUtils.isNotEmpty(name) ? name : "用户#" + userId;
    }

    private String money(BigDecimal v)
    {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }

    private String excerpt(String s)
    {
        if (s == null || s.trim().isEmpty()) return "（无文字）";
        String t = s.trim().replaceAll("\\s+", " ");
        return t.length() <= 60 ? t : t.substring(0, 60) + "…";
    }

    private Long addTimeline(Long orderId, String user, String type, String content, String attachments, Date time)
    {
        JiedanTimeline t = new JiedanTimeline();
        t.setOrderId(orderId);
        t.setUserName(user);
        t.setType(type);
        if ("message".equals(type)) t.setCustomerAuthorId(SecurityUtils.getUserId());
        t.setContent(content);
        t.setAttachments(attachments);
        t.setCreateTime(time);
        timelineMapper.insert(t);
        return t.getId();
    }

    private String publicCustomerName(String name, String account)
    {
        return name == null || name.isBlank() || name.equals(account) ? "客户" : name;
    }

    private JiedanBug insertBug(Long orderId, String user, String content, Object attachments, Date time, String sender, Integer sheetChars, boolean actionable)
    {
        JiedanBug bug = new JiedanBug();
        bug.setOrderId(orderId);
        bug.setContent(content);
        bug.setAttachments(attachmentStorage.normalize(jsonStr(attachments)).getJson());
        bug.setStatus(actionable ? "action" : "open");
        bug.setSenderKind(sender);
        if ("customer".equals(sender)) bug.setCustomerAuthorId(SecurityUtils.getUserId());
        bug.setSheetChars(sheetChars);
        bug.setCreatedBy(user);
        bug.setCreateTime(time);
        bugMapper.insert(bug);
        return bug;
    }

    private JiedanBugUpdate insertBugUpdate(Long bugId, String user, String content, Object attachments, Date time)
    {
        JiedanBugUpdate update = new JiedanBugUpdate();
        update.setBugId(bugId);
        update.setContent(content);
        update.setAttachments(attachmentStorage.normalize(jsonStr(attachments)).getJson());
        update.setCreatedBy(user);
        update.setCreateTime(time);
        bugUpdateMapper.insert(update);
        return update;
    }

    private void touchUnread(JiedanOrder o, Long by, Date now)
    {
        JiedanOrder upd = new JiedanOrder();
        upd.setId(o.getId());
        upd.setUnread(join(othersThan(o.getOwnerId(), by)));
        upd.setUpdateTime(now);
        orderMapper.update(upd);
    }

    private void touchOrder(Long orderId, Date now)
    {
        JiedanOrder upd = new JiedanOrder();
        upd.setId(orderId);
        upd.setUpdateTime(now);
        orderMapper.update(upd);
    }

    private Map<String, Object> toVO(JiedanOrder o, boolean includeInternal)
    {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", o.getId());
        vo.put("orderNo", o.getOrderNo());
        vo.put("title", o.getTitle());
        vo.put("channel", o.getChannel());
        vo.put("customer", o.getCustomer());
        vo.put("customerAccount", o.getCustomerAccount());
        if (includeInternal) vo.put("testerIds", testers.list(o.getId()));
        else vo.put("isPrimaryCustomer", SecurityUtils.getUsername().equals(o.getCustomerAccount()));
        vo.put("contact", o.getContact());
        vo.put("amount", o.getAmount());
        vo.put("ownerId", o.getOwnerId());
        vo.put("status", o.getStatus());
        vo.put("priority", o.getPriority());
        vo.put("deadline", o.getDeadline());
        vo.put("requirement", o.getRequirement());
        vo.put("paid", o.getPaid() != null && o.getPaid() == 1);
        vo.put("revisions", o.getRevisions() == null ? 0 : o.getRevisions());
        vo.put("version", o.getVersion() == null ? 0 : o.getVersion());
        vo.put("createTime", fmt(o.getCreateTime()));
        vo.put("updateTime", fmt(o.getUpdateTime()));
        JSONArray files = normalizedAttachments(o.getAttachments(), "order", o.getId());
        vo.put("attachments", files);
        vo.put("coverUrl", files.stream().filter(Map.class::isInstance).map(Map.class::cast)
                .filter(item -> {
                    String url = Objects.toString(item.get("url"), "");
                    String kind = Objects.toString(item.get("type"), "");
                    return url.startsWith("data:image/") || ((url.startsWith("/") && !url.startsWith("//") && !url.contains("\\"))
                            && ("image".equals(kind) || kind.startsWith("image/") || url.matches("(?i).*\\.(png|jpe?g|gif|webp|bmp|avif|svg)$")));
                })
                .map(item -> item.get("url")).findFirst().orElse(null));
        vo.put("unread", parseUnread(o.getUnread()));
        if (includeInternal)
        {
            // 收款明细属于内部账目，客户看不到
            vo.put("receivedAmount", o.getReceivedAmount() == null ? BigDecimal.ZERO : o.getReceivedAmount());
            vo.put("assignState", strOr(o.getAssignState(), ""));
            List<Map<String, Object>> payments = new ArrayList<>();
            for (JiedanPayment pay : paymentMapper.selectByOrderId(o.getId()))
            {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("id", pay.getId());
                pm.put("amount", pay.getAmount());
                pm.put("payDate", pay.getPayDate());
                pm.put("note", pay.getNote());
                pm.put("createdBy", displayMemberName(pay.getCreatedBy()));
                pm.put("createTime", fmt(pay.getCreateTime()));
                payments.add(pm);
            }
            vo.put("handled", Boolean.TRUE.equals(o.getHandled()));
            vo.put("handledTime", o.getHandledTime());
            vo.put("urgentUntil", o.getUrgentUntil());
            vo.put("recentApprovalEvents", recentHandoffApprovals().getOrDefault(o.getId(), List.of()));
            vo.put("urgentHandoff", urgentHandoff(o.getId()));
            vo.put("taskTurns", JSON.parseObject(orderMapper.selectTaskTurns(o.getId())));
            vo.put("reminderReceipts", parseReceipts(orderMapper.selectReceipts(o.getId())));
            vo.put("payments", payments);
            vo.put("nextStep", o.getNextStep());
            vo.put("nextOwnerId", o.getNextOwnerId());
            vo.put("nextOwnerName", o.getNextOwnerId() == null ? null : ownerName(o.getNextOwnerId()));
            vo.put("nextDue", o.getNextDue());
            // 这个项目里能 @、能接下一步的人（负责人 + 总管理员）
            Set<Long> bossSet = new LinkedHashSet<>(access.bossUserIds());
            List<Map<String, Object>> people = new ArrayList<>();
            for (Long m : collaborators(o))
            {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("id", m);
                pm.put("name", ownerName(m));
                pm.put("boss", bossSet.contains(m));
                pm.put("owner", m.equals(o.getOwnerId()));
                people.add(pm);
            }
            vo.put("collaborators", people);
        }

        if (includeInternal) vo.put("sheetOrigin", IssueSheet.origin(settingMapper.selectValue("push.siteUrl")));
        Map<Long, List<JiedanBugUpdate>> updatesByBug = new LinkedHashMap<>();
        for (JiedanBugUpdate update : bugUpdateMapper.selectByOrderId(o.getId()))
        {
            updatesByBug.computeIfAbsent(update.getBugId(), k -> new ArrayList<>()).add(update);
        }

        Map<Long, Map<String, Object>> reviewsByBug = new LinkedHashMap<>();
        for (Map<String, Object> review : issueReviews.latestByProject(o.getId()))
        {
            Long bugId = asLong(review.remove("bugId"));
            review.remove("decisionHash");
            Long customerId = asLong(review.remove("customerId"));
            if (!includeInternal && !Objects.equals(customerId, SecurityUtils.getUserId())) continue;
            for (String field : Arrays.asList("beforeImage", "afterImage"))
                review.put(field, JSON.parseObject(asStr(review.get(field))));
            reviewsByBug.put(bugId, review);
        }
        List<Map<String, Object>> bugs = new ArrayList<>();
        for (JiedanBug b : bugMapper.selectByOrderId(o.getId()))
        {
            Map<String, Object> bm = new LinkedHashMap<>();
            bm.put("id", b.getId());
            bm.put("orderId", b.getOrderId());
            bm.put("content", b.getContent());
            bm.put("attachments", normalizedAttachments(b.getAttachments(), "bug", b.getId()));
            bm.put("review", reviewsByBug.get(b.getId()));
            bm.put("status", "confirm".equals(b.getStatus()) || (includeInternal && ("action".equals(b.getStatus()) || "ai_review".equals(b.getStatus())))
                    ? b.getStatus() : normalizeBugStatus(b.getStatus()));
            bm.put("progressStatus", !includeInternal && "ai_review".equals(b.getStatus()) ? "open" : b.getStatus());
            if (!includeInternal)
            {
                boolean otherReview = "confirm".equals(b.getStatus()) && !reviewsByBug.containsKey(b.getId());
                if (otherReview) bm.put("progressStatus", "confirm_other");
                bm.put("canManageIssue", canManageCustomerIssue(o, b, SecurityUtils.getUsername()));
            }
            bm.put("createdBy", displayMemberName(b.getCreatedBy()));
            if (includeInternal) IssueSheet.describe(bm, b, o.getCustomer(), o.getCustomerAccount());
            bm.put("time", IssueSheet.time(b.getCreateTime()));

            List<Map<String, Object>> updates = new ArrayList<>();
            for (JiedanBugUpdate u : updatesByBug.getOrDefault(b.getId(), new ArrayList<>()))
            {
                if (!includeInternal && "codex".equals(u.getSenderKind())) continue;
                Map<String, Object> um = new LinkedHashMap<>();
                um.put("id", u.getId());
                um.put("bugId", u.getBugId());
                um.put("content", u.getContent());
                um.put("attachments", normalizedAttachments(u.getAttachments(), "bugUpdate", u.getId()));
                um.put("senderKind", u.getSenderKind());
                um.put("createdBy", displayMemberName(u.getCreatedBy()));
                if (includeInternal && !"codex".equals(u.getSenderKind())) IssueSheet.describeAuthor(um, u.getSenderKind(), u.getCreatedBy(), o.getCustomer(), o.getCustomerAccount());
                um.put("time", IssueSheet.time(u.getCreateTime()));
                updates.add(um);
            }
            bm.put("updates", updates);
            bugs.add(bm);
        }
        vo.put("bugs", bugs);
        if (includeInternal) vo.put("internalReviewIssues", bugs.stream()
                .filter(b -> "ai_review".equals(b.get("status")))
                .map(b -> Map.of("id", b.get("id"), "content", strOr(asStr(b.get("content")), "")))
                .collect(java.util.stream.Collectors.toList()));
        if (includeInternal)
        {
            List<Map<String, Object>> previews = new ArrayList<>();
            for (Map<String, Object> bug : bugs)
            {
                if (!"action".equals(bug.get("status"))) continue;
                String text = Objects.toString(bug.get("content"), "");
                int length = Math.min(120, text.codePointCount(0, text.length()));
                previews.add(Map.of("id", bug.get("id"), "content", text.substring(0, text.offsetByCodePoints(0, length))));
            }
            int newCount = 0;
            int openCount = 0;
            Map<String,Object> latest = null;
            if (!"done".equals(o.getStatus()) && !"cancelled".equals(o.getStatus())) {
                for (Map<String,Object> issue : bugs) {
                    if (!List.of("open","action").contains(issue.get("status"))) continue;
                    openCount++;
                    if ("open".equals(issue.get("status")) && "customer".equals(issue.get("senderKind"))) {
                        newCount++;
                        String body = Objects.toString(issue.get("sheetContent"), "");
                        latest = Map.of("id", issue.get("id"), "content", body.substring(0,body.offsetByCodePoints(0,Math.min(40,body.codePointCount(0,body.length())))));
                    }
                }
            }
            vo.put("newBugCount", newCount);
            vo.put("openBugCount", openCount);
            vo.put("newBugPreview", latest);
            vo.put("actionBugCount", previews.size());
            vo.put("actionBugPreviews", previews);
        }

        // 内部记录上的 @：谁被 @、看没看过（只给后台成员看）
        Map<Long, List<Map<String, Object>>> mentionsByTimeline = new LinkedHashMap<>();
        if (includeInternal)
        {
            Map<Long, String> names = new LinkedHashMap<>();
            for (Map<String, Object> m : mentionMapper.selectByOrderId(o.getId()))
            {
                Long tid = asLong(m.get("timelineId"));
                Long mid = asLong(m.get("memberId"));
                if (tid == null || mid == null) continue;
                Map<String, Object> mv = new LinkedHashMap<>();
                mv.put("memberId", mid);
                mv.put("name", names.computeIfAbsent(mid, this::ownerName));
                mv.put("read", m.get("readTime") != null);
                mentionsByTimeline.computeIfAbsent(tid, k -> new ArrayList<>()).add(mv);
            }
        }
        List<Map<String, Object>> tl = new ArrayList<>();
        for (JiedanTimeline t : timelineMapper.selectByOrderId(o.getId()))
        {
            if ((t.getType() != null && t.getType().startsWith("handoff.")) || !includeInternal && !isChatType(t.getType())) continue;
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("id", t.getId());
            tm.put("time", fmt(t.getCreateTime()));
            tm.put("user", displayMemberName(t.getUserName()));
            tm.put("type", t.getType());
            if (!includeInternal && "message".equals(t.getType()))
                tm.put("mine", SecurityUtils.getUserId().equals(t.getCustomerAuthorId()));
            tm.put("content", t.getContent());
            tm.put("attachments", normalizedAttachments(t.getAttachments(), "timeline", t.getId()));
            List<Map<String, Object>> ms = mentionsByTimeline.get(t.getId());
            if (ms != null) tm.put("mentions", ms);
            tl.add(tm);
        }
        vo.put("timeline", tl);
        return vo;
    }

    private Map<?, ?> parseReceipts(String json)
    {
        return json == null ? java.util.Collections.emptyMap() : JSON.parseObject(json);
    }

    private Map<String, Object> toListSummaryVO(Map<String, Object> row)
    {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", row.get("id"));
        vo.put("orderNo", row.get("orderNo"));
        vo.put("title", row.get("title"));
        vo.put("channel", row.get("channel"));
        vo.put("customer", row.get("customer"));
        vo.put("customerAccount", row.get("customerAccount"));
        vo.put("amount", row.get("amount"));
        vo.put("ownerId", row.get("ownerId"));
        vo.put("status", row.get("status"));
        vo.put("priority", row.get("priority"));
        vo.put("deadline", row.get("deadline"));
        vo.put("requirement", row.get("requirement"));
        vo.put("coverUrl", row.get("coverUrl"));
        vo.put("paid", asBool(row.get("paid")));
        vo.put("revisions", row.get("revisions") == null ? 0 : row.get("revisions"));
        vo.put("version", row.get("version") == null ? 0 : row.get("version"));
        vo.put("createTime", fmtIfDate(row.get("createTime")));
        vo.put("updateTime", fmtIfDate(row.get("updateTime")));
        vo.put("messageCount", row.get("messageCount") == null ? 0 : row.get("messageCount"));

        String lastType = asStr(row.get("lastMessageType"));
        if (lastType != null && !lastType.trim().isEmpty())
        {
            Map<String, Object> last = new LinkedHashMap<>();
            last.put("id", row.get("lastMessageId"));
            last.put("type", lastType);
            if ("message".equals(lastType))
                last.put("mine", SecurityUtils.getUserId().equals(asLong(row.get("lastMessageAuthorId"))));
            last.put("user", displayMemberName(asStr(row.get("lastMessageUser"))));
            last.put("content", row.get("lastMessageContent"));
            last.put("hasAttachments", asBool(row.get("lastMessageHasAttachments")));
            last.put("time", fmtIfDate(row.get("lastMessageTime")));
            vo.put("lastMessage", last);
        }
        return vo;
    }

    private Map<String, Object> toAdminListSummaryVO(Map<String, Object> row)
    {
        Map<String, Object> vo = toListSummaryVO(row);
        String testerList = asStr(row.get("testerIds"));
        vo.put("testerIds", testerList == null ? List.of() : JSON.parseArray(testerList));
        vo.put("handled", asBool(row.get("handled")));
        vo.put("handledTime", row.get("handledTime"));
        String reviews = asStr(row.get("internalReviewIssues"));
        vo.put("internalReviewIssues", reviews == null ? new JSONArray() : JSON.parseArray(reviews));
        vo.put("urgentUntil", row.get("urgentUntil"));
        vo.put("taskTurns", JSON.parseObject(asStr(row.get("taskTurns"))));
        vo.put("urgentHandoff", JSON.parseObject(asStr(row.get("urgentHandoff"))));
        vo.put("contact", row.get("contact"));
        vo.put("receivedAmount", asDecimal(row.get("receivedAmount")));
        vo.put("assignState", strOr(asStr(row.get("assignState")), ""));
        vo.put("pendingApprovals", row.get("pendingApprovals") == null ? 0 : row.get("pendingApprovals"));
        vo.put("unread", parseUnread(asStr(row.get("unread"))));
        vo.put("attachmentCount", row.get("attachmentCount") == null ? 0 : row.get("attachmentCount"));
        vo.put("newBugCount", row.get("newBugCount"));
        vo.put("openBugCount", row.get("openBugCount"));
        String preview = asStr(row.get("newBugPreview"));
        vo.put("newBugPreview", preview == null ? null : JSON.parseObject(preview));
        vo.put("actionBugCount", row.get("actionBugCount"));
        String previews = asStr(row.get("actionBugPreviews"));
        vo.put("actionBugPreviews", previews == null ? new JSONArray() : JSON.parseArray(previews));
        vo.put("lastBugId", row.get("lastBugId"));
        vo.put("lastMessageId", row.get("lastMessageId"));
        if ("1".equals(String.valueOf(row.get("actionBugCount")))) vo.put("actionBugId", row.get("actionBugId"));
        vo.put("bugCount", row.get("bugCount") == null ? 0 : row.get("bugCount"));
        vo.put("reminderReceipts", parseReceipts(asStr(row.get("reminderReceipts"))));
        vo.put("nextStep", row.get("nextStep"));
        vo.put("nextOwnerId", row.get("nextOwnerId"));
        vo.put("nextDue", row.get("nextDue"));
        if (row.get("mentionTime") != null)
        {
            Map<String, Object> mention = new LinkedHashMap<>();
            mention.put("from", displayMemberName(asStr(row.get("mentionFrom"))));
            mention.put("content", row.get("mentionContent"));
            mention.put("time", fmtIfDate(row.get("mentionTime")));
            vo.put("myMention", mention);
        }
        // 最近一条动态：消息中心直接写出「谁做了什么」（只给内部列表，客户端列表不带）
        String eventType = asStr(row.get("lastEventType"));
        if (eventType != null && !eventType.trim().isEmpty())
        {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", eventType);
            event.put("user", displayMemberName(asStr(row.get("lastEventUser"))));
            event.put("content", excerpt(asStr(row.get("lastEventContent"))));
            event.put("time", fmtIfDate(row.get("lastEventTime")));
            vo.put("lastEvent", event);
        }
        return vo;
    }

    private Map<String, Object> versionVO(Long id, Long version)
    {
        if (version == null) return null;
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", id);
        vo.put("version", version);
        return vo;
    }

    private JSONArray normalizedAttachments(String json, String ownerType, Long ownerId)
    {
        JiedanAttachmentStorage.NormalizedAttachments normalized = attachmentStorage.normalize(json);
        if (normalized.isChanged())
        {
            if ("order".equals(ownerType)) orderMapper.updateAttachmentsOnly(ownerId, normalized.getJson());
            else if ("note".equals(ownerType)) orderMapper.updateNoteAttachmentsOnly(ownerId, normalized.getJson());
            else if ("bug".equals(ownerType)) bugMapper.updateAttachments(ownerId, normalized.getJson());
            else if ("bugUpdate".equals(ownerType)) bugUpdateMapper.updateAttachments(ownerId, normalized.getJson());
            else if ("timeline".equals(ownerType)) timelineMapper.updateAttachments(ownerId, normalized.getJson());
        }
        return normalized.getItems();
    }

    private boolean isChatType(String type)
    {
        return "message".equals(type) || "reply".equals(type);
    }

    private String displayMemberName(String name)
    {
        return IssueSheet.memberName(name);
    }

    private boolean isBlankBug(Object content, Object attachments)
    {
        String text = asStr(content);
        if (text != null && !text.trim().isEmpty()) return false;
        if (attachments == null) return true;
        if (attachments instanceof List) return ((List<?>) attachments).isEmpty();
        String s = attachments.toString().trim();
        return s.isEmpty() || "[]".equals(s);
    }

    private String normalizeBugStatus(Object status)
    {
        return "resolved".equals(asStr(status)) ? "resolved" : "open";
    }

    private String bugStatusLabel(String status)
    {
        return "resolved".equals(status) ? "已解决" : "未解决";
    }

    private String dash(String s)
    {
        return (s == null || s.trim().isEmpty()) ? "" : " · " + s;
    }

    private List<Long> parseUnread(String csv)
    {
        List<Long> list = new ArrayList<>();
        if (csv != null && !csv.trim().isEmpty())
        {
            for (String s : csv.split(","))
            {
                if (!s.trim().isEmpty()) list.add(Long.parseLong(s.trim()));
            }
        }
        return list;
    }

    private String join(List<Long> ids)
    {
        List<String> s = new ArrayList<>();
        for (Long id : ids) s.add(String.valueOf(id));
        return String.join(",", s);
    }

    private String jsonStr(Object o)
    {
        if (o == null) return null;
        if (o instanceof String) return (String) o;
        return JSON.toJSONString(o);
    }

    private String fmt(Date d)
    {
        return d == null ? "" : new SimpleDateFormat("yyyy-MM-dd HH:mm").format(d);
    }

    private String fmtIfDate(Object value)
    {
        if (value == null) return "";
        if (value instanceof Date) return fmt((Date) value);
        // 自定义 SQL 返回的 datetime 列在新版驱动下是 LocalDateTime，统一成和详情一样的“年-月-日 时:分”
        if (value instanceof java.time.LocalDateTime)
        {
            return ((java.time.LocalDateTime) value).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        }
        return value.toString();
    }

    private String year(Date d)
    {
        return new SimpleDateFormat("yyyy").format(d);
    }

    private Long asLong(Object o)
    {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(o.toString().trim()); } catch (Exception e) { return null; }
    }

    private BigDecimal asDecimal(Object o)
    {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof Number) return new BigDecimal(o.toString());
        try { return new BigDecimal(o.toString().trim()); } catch (Exception e) { return BigDecimal.ZERO; }
    }

    private boolean asBool(Object o)
    {
        if (o == null) return false;
        if (o instanceof Boolean) return (Boolean) o;
        String s = o.toString().trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    private String asStr(Object o)
    {
        return o == null ? null : o.toString();
    }

    private String strOr(String v, String def)
    {
        return (v == null || v.trim().isEmpty()) ? def : v;
    }
}
