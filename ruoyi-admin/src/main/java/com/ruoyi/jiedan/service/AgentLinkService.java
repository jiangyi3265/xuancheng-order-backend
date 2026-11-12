package com.ruoyi.jiedan.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.jiedan.domain.JiedanBug;
import com.ruoyi.jiedan.domain.JiedanBugUpdate;
import com.ruoyi.jiedan.mapper.JiedanBugMapper;
import com.ruoyi.jiedan.mapper.JiedanBugUpdateMapper;
import com.ruoyi.jiedan.mapper.JiedanSettingMapper;

@Service
public class AgentLinkService
{
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int READS_PER_MINUTE = 30;
    private final JdbcTemplate db;
    private final JiedanBugMapper bugs;
    private final JiedanBugUpdateMapper updates;
    private final JiedanSettingMapper settings;
    private final JiedanAttachmentStorage media;
    private final JiedanAccess access;
    private final JiedanAuditService audit;
    private final IJiedanOrderService orders;

    public AgentLinkService(JdbcTemplate db, JiedanBugMapper bugs, JiedanBugUpdateMapper updates,
                            JiedanSettingMapper settings, JiedanAttachmentStorage media, JiedanAccess access, JiedanAuditService audit,
                            @org.springframework.context.annotation.Lazy IJiedanOrderService orders)
    {
        this.orders = orders;
        this.db = db;
        this.bugs = bugs;
        this.updates = updates;
        this.settings = settings;
        this.media = media;
        this.access = access;
        this.audit = audit;
    }

    private String origin(String requestOrigin)
    {
        String configured = IssueSheet.origin(settings.selectValue("push.siteUrl"));
        String value = configured.isEmpty() ? IssueSheet.origin(requestOrigin) : configured;
        if (value.isEmpty()) throw new ServiceException("请在通知设置中填写系统网址");
        return value;
    }

    private String projectForMember(long id)
    {
        Long owner = access.ownerScope();
        List<String> result = db.queryForList(
                "select title from jiedan_order where id=? and del_flag=0 and (? is null or owner_id=?) for update",
                String.class, id, owner, owner);
        if (result.isEmpty()) throw new ServiceException(JiedanAccess.DENIED);
        return result.get(0);
    }

    private Map<String, Object> status(long id)
    {
        List<Map<String, Object>> rows = db.queryForList(
                "select case when disabled=1 then 'disabled' when expires_at<=utc_timestamp() then 'expired' else 'active' end as state,"
                + " left(sha2(concat('issue-link-version:',token_hash),256),24) as revision,"
                + " date_format(date_add(expires_at, interval 8 hour), '%Y-%m-%d %H:%i:%s') as expiresAt,"
                + " date_format(date_add(last_read_at, interval 8 hour), '%Y-%m-%d %H:%i:%s') as lastReadAt"
                + ", (select left(sha2(k.secret_hash,256),24) from jiedan_agent_reply_key k where k.order_id=l.order_id and k.read_hash=l.token_hash) as replyRevision"
                + " from jiedan_agent_link l where l.order_id=?", id);
        if (!rows.isEmpty()) return new LinkedHashMap<>(rows.get(0));
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("state", "none");
        empty.put("revision", null);
        empty.put("expiresAt", null);
        empty.put("lastReadAt", null);
        return empty;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> get(long id)
    {
        projectForMember(id);
        return status(id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> change(long id, String action, String requestOrigin)
    {
        String title = projectForMember(id);
        Map<String, Object> current = status(id);
        String state = current.get("state").toString();
        if ("disable".equals(action))
        {
            if (!"none".equals(state) && !"disabled".equals(state))
            {
                db.update("update jiedan_agent_link set disabled=1 where order_id=?", id);
                audit.recordRequired("agent.disable", "order", id, title, "停用 AI 问题清单链接");
            }
            return status(id);
        }
        boolean rotate = "regenerate".equals(action);
        if (!rotate && !"generate".equals(action)) throw new ServiceException("未知操作");
        if (!rotate && "active".equals(state)) throw new ServiceException("已有有效链接，需要换链接请点重新生成");
        String site = origin(requestOrigin);
        normalizeOldMedia(id);
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String digest = DigestUtils.sha256Hex(token);
        if ("none".equals(state))
            db.update("insert into jiedan_agent_link(order_id,token_hash,created_by,created_at,expires_at)"
                    + " values(?,?,?,utc_timestamp(),date_add(utc_timestamp(),interval 90 day))", id, digest, access.currentUserId());
        else
            db.update("update jiedan_agent_link set token_hash=?,created_by=?,created_at=utc_timestamp(),"
                    + "expires_at=date_add(utc_timestamp(),interval 90 day),disabled=0,last_read_at=null,read_window='' where order_id=?",
                    digest, access.currentUserId(), id);
        audit.recordRequired("agent." + action, "order", id, title, (rotate ? "重新生成" : "生成") + " AI 问题清单链接");
        Map<String, Object> result = status(id);
        result.put("url", site + "/jiedan/agent/sheet/" + token);
        return result;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String,Object> replyKey(long id, boolean enable)
    {
        projectForMember(id);
        if (!enable) {
            db.update("update jiedan_agent_reply_key set secret_hash=null where order_id=?", id);
            return Map.of("enabled", false);
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        int changed = db.update("insert into jiedan_agent_reply_key(order_id,secret_hash,read_hash,reader_id) "
                + "select order_id,?,token_hash,null from jiedan_agent_link where order_id=? and disabled=0 and expires_at>utc_timestamp() "
                + "on duplicate key update secret_hash=values(secret_hash),read_hash=values(read_hash),reader_id=values(reader_id)", DigestUtils.sha256Hex(secret), id);
        if (changed == 0) throw new ServiceException("请先生成有效的 AI 只读链接");
        return Map.of("secret", secret, "replyRevision", status(id).get("replyRevision"));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void reply(long bug, String secret, Map<String,String> body)
    {
        if (secret == null || !secret.matches("[A-Za-z0-9_-]{43}")) throw new ServiceException("回复凭据无效", 403);
        String digest = DigestUtils.sha256Hex(secret);
        List<Long> projects = db.queryForList("select order_id from jiedan_agent_reply_key where secret_hash=?", Long.class,digest);
        if (projects.isEmpty()) throw new ServiceException("回复凭据无效", 403);
        long id = projects.get(0);
        List<String> project = db.queryForList("select status from jiedan_order where id=? and del_flag=0 for update", String.class,id);
        String valid = "select count(*) from jiedan_agent_reply_key k join jiedan_agent_link l on l.order_id=k.order_id "
                + "and l.token_hash=k.read_hash where k.order_id=? and k.secret_hash=? and l.disabled=0 and l.expires_at>utc_timestamp()";
        if (project.isEmpty() || db.queryForObject(valid,Integer.class,id,digest) != 1)
            throw new ServiceException("回复凭据无效",403);
        JiedanBug issue = bugs.lockById(bug);
        if (issue == null || !Long.valueOf(id).equals(issue.getOrderId())) throw new ServiceException("需求不存在",404);
        String change = replyText(body,"changes"), tests = replyText(body,"tests");
        String request = body.getOrDefault("requestId","");
        if (request == null || !request.matches("[a-zA-Z0-9_-]{8,64}")) throw new ServiceException("请提供唯一的 requestId");
        String hash = DigestUtils.sha256Hex(change.length()+":"+change+tests);
        List<String> prior = db.queryForList("select payload_hash from jiedan_bug_reply_meta where bug_id=? and sender_kind='codex' and request_key=?",String.class,bug,request);
        if (!prior.isEmpty()) {
            if (!hash.equals(prior.get(0))) throw new ServiceException("该 requestId 已用于其他回复",409);
            return;
        }
        if (List.of("done","cancelled").contains(project.get(0)) ||
            !(issue.getStatus()==null || issue.getStatus().isEmpty() || List.of("open","action").contains(issue.getStatus())))
            throw new ServiceException("当前需求不接受新的修改回报",409);
        // 完整回报（含测试结果）只给团队看，署名是项目负责人，不再显示「Codex」
        JiedanBugUpdate row = new JiedanBugUpdate();
        row.setBugId(bug); row.setCreatedBy(orders.projectOwnerName(id)); row.setCreateTime(new java.util.Date());
        row.setContent("修改内容："+change+"\n测试结果："+tests);
        updates.insert(row);
        updates.insertMeta(row.getId(),bug,"codex",0L,request,hash);
        // 客户提的问题：改完就是「已处理」，以负责人的名义把修改内容发给客户确认，客户确认后才是已解决；
        // 团队自己记的问题、没绑客户账号的项目，仍然先交给内部验收
        if (!orders.finishIssueForCustomer(id, bug, change)) bugs.updateStatus(bug,"ai_review");
        db.update("update jiedan_order set version=version+1,update_time=now() where id=?",id);
    }

    public List<Map<String,Object>> reports()
    {
        Long scope = access.ownerScope();
        return db.queryForList("select o.id as orderId,o.owner_id as ownerId,o.title,b.id as bugId,substring_index(substring_index(left(b.content,coalesce(b.sheet_chars,char_length(b.content))),concat(char(10),'联系方式：'),1),concat(char(10),'参考预算：'),1) as issue,b.status,u.content as report,"
                + "date_format(u.create_time,'%Y-%m-%d %H:%i') as time,u.id from jiedan_bug_reply_meta m "
                + "join jiedan_bug_update u on u.id=m.update_id join jiedan_bug b on b.id=u.bug_id "
                + "join jiedan_order o on o.id=b.order_id "
                + "where m.sender_kind='codex' and o.del_flag=0 and (? is null or o.owner_id=?) order by u.id desc",
                scope,scope);
    }

    private String replyText(Map<String,String> body,String field)
    {
        String value = body.get(field);
        if (value == null || value.isBlank() || value.length()>4000) throw new ServiceException("修改内容和测试结果各需填写 1 至 4000 字");
        return value.trim();
    }

    // Legacy inline images become permanent media URLs on authenticated link creation.
    // Anonymous reads never create files or change issue content.
    private void normalizeOldMedia(long id)
    {
        for (JiedanBug bug : bugs.selectByOrderId(id))
        {
            if (bug.getAttachments() == null || !bug.getAttachments().contains("data:")) continue;
            JiedanAttachmentStorage.NormalizedAttachments normalized = media.normalize(bug.getAttachments());
            if (normalized.isChanged()) bugs.updateAttachments(bug.getId(), normalized.getJson());
        }
        for (JiedanBugUpdate reply : updates.selectByOrderId(id))
        {
            if (reply.getAttachments() == null || !reply.getAttachments().contains("data:")) continue;
            JiedanAttachmentStorage.NormalizedAttachments normalized = media.normalize(reply.getAttachments());
            if (normalized.isChanged()) updates.updateAttachments(reply.getId(), normalized.getJson());
        }
    }

    public static class RateLimit extends RuntimeException
    {
        private static final long serialVersionUID = 1L;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public String read(String token, boolean all, String requestOrigin, boolean html)
    {
        if (!token.matches("[A-Za-z0-9_-]{43}")) return null;
        String digest = DigestUtils.sha256Hex(token);
        List<Long> ids = db.queryForList("select order_id from jiedan_agent_link where token_hash=? and disabled=0 and expires_at>utc_timestamp()", Long.class, digest);
        if (ids.isEmpty()) return null;
        long id = ids.get(0);
        // All writers acquire the project first. Recheck the digest after locking so rotation, deletion
        // and simultaneous reads cannot reuse an old link or spend the same quota slot twice.
        List<Map<String, Object>> projects = db.queryForList(
                "select title,order_no,customer,customer_account from jiedan_order where id=? and del_flag=0 for update", id);
        if (projects.isEmpty()) return null;
        List<Map<String, Object>> links = db.queryForList(
                "select read_window,cast(unix_timestamp(current_timestamp(3))*1000 as signed) as nowMs"
                + " from jiedan_agent_link where order_id=? and token_hash=? and disabled=0 and expires_at>utc_timestamp() for update", id, digest);
        if (links.isEmpty()) return null;
        Map<String, Object> link = links.get(0);
        long now = ((Number) link.get("nowMs")).longValue();
        List<String> window = new ArrayList<>();
        for (String time : link.get("read_window").toString().split(","))
            if (!time.isEmpty() && Long.parseLong(time) > now - 60000L) window.add(time);
        if (window.size() >= READS_PER_MINUTE) throw new RateLimit();
        window.add(Long.toString(now));
        Map<String, Object> project = projects.get(0);
        String text = IssueSheet.render((String) project.get("title"), (String) project.get("order_no"),
                (String) project.get("customer"), (String) project.get("customer_account"), bugs.selectByOrderId(id),
                updates.selectByOrderId(id), origin(requestOrigin), Instant.ofEpochMilli(now), all, html);
        db.update("update jiedan_agent_link set last_read_at=utc_timestamp(),read_window=? where order_id=?", String.join(",", window), id);
        return text;
    }
}
