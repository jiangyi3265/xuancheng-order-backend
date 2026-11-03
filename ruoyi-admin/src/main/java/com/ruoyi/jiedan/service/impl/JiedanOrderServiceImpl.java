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

import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.jiedan.domain.JiedanBug;
import com.ruoyi.jiedan.domain.JiedanBugUpdate;
import com.ruoyi.jiedan.domain.JiedanOrder;
import com.ruoyi.jiedan.domain.JiedanPayment;
import com.ruoyi.jiedan.domain.JiedanSetting;
import com.ruoyi.jiedan.domain.JiedanTimeline;
import com.ruoyi.jiedan.mapper.JiedanAccountMapper;
import com.ruoyi.jiedan.mapper.JiedanBugMapper;
import com.ruoyi.jiedan.mapper.JiedanBugUpdateMapper;
import com.ruoyi.jiedan.mapper.JiedanOrderMapper;
import com.ruoyi.jiedan.mapper.JiedanPaymentMapper;
import com.ruoyi.jiedan.mapper.JiedanSettingMapper;
import com.ruoyi.jiedan.mapper.JiedanTimelineMapper;
import com.ruoyi.jiedan.service.IJiedanOrderService;
import com.ruoyi.jiedan.service.JiedanAccess;
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
    private JiedanTimelineMapper timelineMapper;

    @Autowired
    private JiedanPaymentMapper paymentMapper;

    @Autowired
    private JiedanBugMapper bugMapper;

    @Autowired
    private JiedanBugUpdateMapper bugUpdateMapper;

    @Autowired
    private JiedanSettingMapper settingMapper;

    @Autowired
    private JiedanPushService pushService;

    @Autowired
    private JiedanAttachmentStorage attachmentStorage;

    @Autowired
    private JiedanAccess access;

    @Autowired
    private JiedanAuditService audit;

    @Autowired
    private JiedanAccountMapper accountMapper;

    private static final Map<String, String> PRIORITY_LABELS = new LinkedHashMap<String, String>()
    {{
        put("high", "高");
        put("medium", "中");
        put("low", "低");
    }};

    private static final Map<String, String> LEGACY_MEMBER_NAMES = new LinkedHashMap<String, String>()
    {{
        put("阿成", "尚琦凯");
        put("阿伟", "费鹏飞");
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
        for (Map<String, Object> row : orderMapper.selectAdminSummaries(access.ownerScope()))
        {
            list.add(toAdminListSummaryVO(row));
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
    public List<Map<String, Object>> listByCustomer(String account)
    {
        List<Map<String, Object>> list = new ArrayList<>();
        if (account == null || account.trim().isEmpty()) return list;
        for (Map<String, Object> row : orderMapper.selectCustomerSummaries(account))
        {
            list.add(toListSummaryVO(row));
        }
        return list;
    }

    @Override
    public Map<String, Object> getForCustomer(Long id, String account)
    {
        JiedanOrder o = orderMapper.selectById(id);
        if (o == null || account == null || !account.equals(o.getCustomerAccount())) return null;
        return toVO(o, false);
    }

    @Override
    public Map<String, Object> getVersionForCustomer(Long id, String account)
    {
        return versionVO(id, orderMapper.selectVersionForCustomer(id, account));
    }

    // ---------- 客户留言：负责人与总管理员同时收到并标记未读 ----------
    @Override
    public Map<String, Object> customerMessage(Long orderId, String account, String customerName,
                                               String content, Object attachments)
    {
        JiedanOrder o = orderMapper.selectById(orderId);
        if (o == null || account == null || !account.equals(o.getCustomerAccount())) return null;
        Date now = new Date();
        String name = strOr(customerName, strOr(o.getCustomer(), "客户"));
        addTimeline(orderId, name, "message", content, jsonStr(attachments), now);
        markUnreadForCustomerAction(o, now);
        String brief = strOr(content, "[消息]");
        pushRecipients(o, null, "💬 客户留言", o.getTitle() + "：" + brief + "（" + name + "）");
        return getForCustomer(orderId, account);
    }

    // ---------- 新增（建单即派单 + 推送） ----------
    @Override
    @Transactional
    public Map<String, Object> create(Map<String, Object> p)
    {
        Long by = access.currentUserId();
        Long owner = by;
        if (access.isBoss())
        {
            Long requested = asLong(p.get("ownerId"));
            if (requested != null)
            {
                if (!access.isAssignable(requested)) throw new ServiceException("负责人无效或账号已停用");
                owner = requested;
            }
        }
        String customerAccount = asStr(p.get("customerAccount"));
        access.requireBindableCustomer(customerAccount);
        return insertOrder(p, owner, customerAccount, by, access.currentName());
    }

    /** 客户端提交需求：自动落到该客户归属管理员的名下。 */
    @Override
    @Transactional
    public Map<String, Object> createFromCustomer(Map<String, Object> p, String account)
    {
        Long owner = access.ownerOfAccount(SecurityUtils.getUserId());
        Map<String, Object> q = new LinkedHashMap<>(p);
        q.put("channel", "form");
        q.put("status", "pending");
        if (strOr(asStr(q.get("customer")), null) == null) q.put("customer", account);
        String byName = strOr(asStr(q.get("byUserName")), "客户：" + account);
        return insertOrder(q, owner, account, null, byName);
    }

    private Map<String, Object> insertOrder(Map<String, Object> p, Long ownerId, String customerAccount,
                                            Long by, String byName)
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
        o.setRevisions(0);
        o.setCreateTime(now);
        o.setUpdateTime(now);
        orderMapper.insert(o);

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

    // ---------- 修改 ----------
    @Override
    public Map<String, Object> update(Map<String, Object> p)
    {
        Long id = asLong(p.get("id"));
        JiedanOrder cur = guard(id);
        Long by = access.currentUserId();
        boolean boss = access.isBoss();

        JiedanOrder o = new JiedanOrder();
        o.setId(id);
        if (p.containsKey("title")) o.setTitle(asStr(p.get("title")));
        if (p.containsKey("channel")) o.setChannel(asStr(p.get("channel")));
        if (p.containsKey("customer")) o.setCustomer(asStr(p.get("customer")));
        if (p.containsKey("customerAccount"))
        {
            String account = asStr(p.get("customerAccount"));
            if (!Objects.equals(strOr(account, ""), strOr(cur.getCustomerAccount(), "")))
            {
                access.requireBindableCustomer(account);
            }
            o.setCustomerAccount(account);
        }
        if (p.containsKey("contact")) o.setContact(asStr(p.get("contact")));
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
                if (!boss) throw new ServiceException("下属管理员不能转派项目");
                if (!access.isAssignable(requested)) throw new ServiceException("负责人无效或账号已停用");
                o.setOwnerId(requested);
                newOwner = requested;
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
        orderMapper.update(o);

        if (newOwner != null && !newOwner.equals(cur.getOwnerId()) && !newOwner.equals(by))
        {
            pushService.pushToMember(newOwner, "📌 项目已派给你", cur.getTitle() + dash(cur.getCustomer()));
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
        JiedanOrder o = guard(id);
        Long by = access.currentUserId();
        String byName = access.currentName();
        orderMapper.softDelete(id, byName, by, new Date());
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
        JiedanOrder o = orderMapper.selectById(id);
        if (o == null || account == null || !account.equals(o.getCustomerAccount())) return false;
        if (!"pending".equals(o.getStatus()))
        {
            throw new ServiceException("该项目已在处理中，不能自行删除，如需撤销请在项目里留言联系我们");
        }
        String byName = "客户：" + access.currentName();
        orderMapper.softDelete(id, byName, null, new Date());
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
        paymentMapper.deleteByOrderId(id);
        bugUpdateMapper.deleteByOrderId(id);
        bugMapper.deleteByOrderId(id);
        timelineMapper.deleteByOrderId(id);
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
        insertBug(o.getId(), byName, asStr(p.get("content")), p.get("attachments"), now);
        touchUnread(o, by, now);
        pushRecipients(o, by, "新 Bug", o.getTitle() + dash(byName));
        return voOf(o.getId());
    }

    @Override
    @Transactional
    public Map<String, Object> deleteBug(Long bugId)
    {
        JiedanBug bug = bugMapper.selectById(bugId);
        if (bug == null) return null;
        JiedanOrder o = guard(bug.getOrderId());
        bugUpdateMapper.deleteByBugId(bugId);
        bugMapper.deleteById(bugId);
        touchOrder(o.getId(), new Date());
        audit.record("bug.delete", "bug", bugId, o.getTitle(), "删除 Bug：" + excerpt(bug.getContent()));
        return voOf(o.getId());
    }

    @Override
    @Transactional
    public Map<String, Object> addBugUpdate(Long bugId, Map<String, Object> p)
    {
        JiedanBug bug = bugMapper.selectById(bugId);
        if (bug == null) return null;
        JiedanOrder o = guard(bug.getOrderId());
        if (isBlankBug(p.get("content"), p.get("attachments"))) return null;

        Long by = access.currentUserId();
        String byName = access.currentName();
        Date now = new Date();
        insertBugUpdate(bugId, byName, asStr(p.get("content")), p.get("attachments"), now);
        touchUnread(o, by, now);
        pushRecipients(o, by, "Bug 追加 QA", o.getTitle() + dash(byName));
        return voOf(o.getId());
    }

    @Override
    @Transactional
    public Map<String, Object> changeBugStatus(Long bugId, Map<String, Object> p)
    {
        JiedanBug bug = bugMapper.selectById(bugId);
        if (bug == null) return null;
        JiedanOrder o = guard(bug.getOrderId());

        String status = normalizeBugStatus(p == null ? null : p.get("status"));
        bugMapper.updateStatus(bugId, status);

        Long by = access.currentUserId();
        String byName = access.currentName();
        touchUnread(o, by, new Date());
        pushRecipients(o, by, "Bug 状态更新", o.getTitle() + dash(byName) + dash(bugStatusLabel(status)));
        return voOf(o.getId());
    }

    @Override
    @Transactional
    public Map<String, Object> createBugForCustomer(Map<String, Object> p, String account, String customerName)
    {
        Long orderId = asLong(p.get("orderId"));
        JiedanOrder o = orderMapper.selectById(orderId);
        if (o == null || account == null || !account.equals(o.getCustomerAccount())) return null;
        if (isBlankBug(p.get("content"), p.get("attachments"))) return null;

        Date now = new Date();
        String name = strOr(customerName, strOr(o.getCustomer(), "客户"));
        insertBug(orderId, name, asStr(p.get("content")), p.get("attachments"), now);

        markUnreadForCustomerAction(o, now);
        pushRecipients(o, null, "客户提交 Bug", o.getTitle() + dash(name));
        return getForCustomer(orderId, account);
    }

    @Override
    @Transactional
    public Map<String, Object> deleteBugForCustomer(Long bugId, String account)
    {
        JiedanBug bug = bugMapper.selectById(bugId);
        if (bug == null) return null;
        JiedanOrder o = orderMapper.selectById(bug.getOrderId());
        if (o == null || account == null || !account.equals(o.getCustomerAccount())) return null;
        bugUpdateMapper.deleteByBugId(bugId);
        bugMapper.deleteById(bugId);
        markUnreadForCustomerAction(o, new Date());
        audit.record("bug.delete", "bug", bugId, o.getTitle(), "客户删除 Bug：" + excerpt(bug.getContent()));
        return getForCustomer(o.getId(), account);
    }

    @Override
    @Transactional
    public Map<String, Object> addBugUpdateForCustomer(Long bugId, Map<String, Object> p, String account, String customerName)
    {
        JiedanBug bug = bugMapper.selectById(bugId);
        if (bug == null) return null;
        JiedanOrder o = orderMapper.selectById(bug.getOrderId());
        if (o == null || account == null || !account.equals(o.getCustomerAccount())) return null;
        if (isBlankBug(p.get("content"), p.get("attachments"))) return null;

        Date now = new Date();
        String name = strOr(customerName, strOr(o.getCustomer(), "客户"));
        insertBugUpdate(bugId, name, asStr(p.get("content")), p.get("attachments"), now);

        markUnreadForCustomerAction(o, now);
        pushRecipients(o, null, "客户追加 Bug QA", o.getTitle() + dash(name));
        return getForCustomer(o.getId(), account);
    }

    @Override
    @Transactional
    public Map<String, Object> changeBugStatusForCustomer(Long bugId, Map<String, Object> p, String account, String customerName)
    {
        JiedanBug bug = bugMapper.selectById(bugId);
        if (bug == null) return null;
        JiedanOrder o = orderMapper.selectById(bug.getOrderId());
        if (o == null || account == null || !account.equals(o.getCustomerAccount())) return null;

        String status = normalizeBugStatus(p == null ? null : p.get("status"));
        bugMapper.updateStatus(bugId, status);

        Date now = new Date();
        String name = strOr(customerName, strOr(o.getCustomer(), "客户"));
        markUnreadForCustomerAction(o, now);
        pushRecipients(o, null, "客户更新 Bug 状态", o.getTitle() + dash(name) + dash(bugStatusLabel(status)));
        return getForCustomer(o.getId(), account);
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
        Date now = new Date();
        addTimeline(o.getId(), byName, type, asStr(p.get("content")), jsonStr(p.get("attachments")), now);
        touchUnread(o, by, now);
        pushRecipients(o, by, "💬 新动态",
                o.getTitle() + "：" + strOr(asStr(p.get("content")), "有新进度") + "（" + byName + "）");
        return voOf(o.getId());
    }

    // ---------- 改状态 ----------
    @Override
    public Map<String, Object> changeStatus(Map<String, Object> p)
    {
        JiedanOrder o = guard(asLong(p.get("id")));
        Long by = access.currentUserId();
        String byName = access.currentName();
        String status = checkStatus(asStr(p.get("status")));
        Date now = new Date();

        JiedanOrder upd = new JiedanOrder();
        upd.setId(o.getId());
        upd.setStatus(status);
        upd.setUpdateTime(now);
        orderMapper.update(upd);

        String label = STATUS_LABELS.getOrDefault(status, status);
        addTimeline(o.getId(), byName, "status", "状态更新为「" + label + "」", null, now);
        audit.record("order.status", "order", o.getId(), o.getTitle(),
                "状态 " + STATUS_LABELS.getOrDefault(o.getStatus(), strOr(o.getStatus(), "无")) + " → " + label);
        touchUnread(o, by, now);
        pushRecipients(o, by, "🔔 " + label, o.getTitle() + "（" + byName + "）");
        return voOf(o.getId());
    }

    // ---------- 标记已读（只能标记自己的） ----------
    @Override
    public Map<String, Object> markRead(Long id, Long memberId)
    {
        JiedanOrder o = guard(id);
        Long me = access.currentUserId();
        List<Long> unread = parseUnread(o.getUnread());
        if (unread.remove(me))
        {
            JiedanOrder upd = new JiedanOrder();
            upd.setId(id);
            upd.setUnread(join(unread));
            orderMapper.update(upd);
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
    public Map<String, Object> nudge(Map<String, Object> p)
    {
        JiedanOrder o = guard(asLong(p.get("id")));
        Long by = access.currentUserId();
        String byName = access.currentName();
        Date now = new Date();
        // 催谁：优先催负责人；若自己就是负责人则催总管理员
        Long target = o.getOwnerId();
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
        addTimeline(o.getId(), byName, "note", byName + " 催了一下：" + o.getTitle(), null, now);
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
            pushService.pushToMember(target, "⏰ 催办", o.getTitle() + " —— " + byName + " 催你跟进");
        }
        return voOf(o.getId());
    }

    // ---------- 推送设置 ----------
    @Override
    public Map<String, String> getPushConfig()
    {
        boolean boss = access.isBoss();
        String mine = sendKeyOf(access.currentUserId());
        Map<String, String> cfg = new LinkedHashMap<>();
        for (JiedanSetting s : settingMapper.selectAll())
        {
            String key = s.getSettingKey();
            // 下属管理员看不到别人的 SendKey
            if (boss || !key.startsWith("push.sendkey.") || key.equals(mine))
            {
                cfg.put(key, s.getSettingValue());
            }
        }
        if (!boss && !cfg.containsKey(mine)) cfg.put(mine, "");
        return cfg;
    }

    @Override
    public void savePushConfig(Map<String, Object> p)
    {
        boolean boss = access.isBoss();
        String mine = sendKeyOf(access.currentUserId());
        for (Map.Entry<String, Object> e : p.entrySet())
        {
            if (e.getKey() == null || !e.getKey().startsWith("push.")) continue;
            if (!boss && !e.getKey().equals(mine)) throw new ServiceException("无权修改该推送设置");
            settingMapper.upsert(e.getKey(), e.getValue() == null ? "" : e.getValue().toString());
        }
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
                    o.getTitle() + dash(o.getCustomer()) + " 截止 " + dl);
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
        JiedanOrder o = guard(orderId);
        BigDecimal amount = parseMoney(p.get("amount"));
        if (amount.signum() <= 0) throw new ServiceException("收款金额要大于 0");
        if (amount.compareTo(PAYMENT_MAX) > 0) throw new ServiceException("收款金额太大，请检查是否多输了几位");

        String payDate = strOr(asStr(p.get("payDate")), LocalDate.now().toString()).trim();
        LocalDate day;
        try { day = LocalDate.parse(payDate); }
        catch (Exception e) { throw new ServiceException("收款日期格式不对，应为 年-月-日"); }
        if (day.isAfter(LocalDate.now().plusDays(1))) throw new ServiceException("收款日期不能是将来的日期");

        String note = strOr(asStr(p.get("note")), "").trim();
        if (note.length() > 200) throw new ServiceException("备注最多 200 个字");

        Date now = new Date();
        JiedanPayment pay = new JiedanPayment();
        pay.setOrderId(orderId);
        pay.setAmount(amount);
        pay.setPayDate(day.toString());
        pay.setNote(note);
        pay.setCreatedBy(access.currentName());
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
        for (Long m : othersThan(o.getOwnerId(), by))
        {
            pushService.pushToMember(m, title, content);
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

    private void addTimeline(Long orderId, String user, String type, String content, String attachments, Date time)
    {
        JiedanTimeline t = new JiedanTimeline();
        t.setOrderId(orderId);
        t.setUserName(user);
        t.setType(type);
        t.setContent(content);
        t.setAttachments(attachments);
        t.setCreateTime(time);
        timelineMapper.insert(t);
    }

    private JiedanBug insertBug(Long orderId, String user, String content, Object attachments, Date time)
    {
        JiedanBug bug = new JiedanBug();
        bug.setOrderId(orderId);
        bug.setContent(content);
        bug.setAttachments(jsonStr(attachments));
        bug.setStatus("open");
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
        update.setAttachments(jsonStr(attachments));
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
        vo.put("attachments", normalizedAttachments(o.getAttachments(), "order", o.getId()));
        vo.put("unread", parseUnread(o.getUnread()));
        if (includeInternal)
        {
            // 收款明细属于内部账目，客户看不到
            vo.put("receivedAmount", o.getReceivedAmount() == null ? BigDecimal.ZERO : o.getReceivedAmount());
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
            vo.put("payments", payments);
        }

        Map<Long, List<JiedanBugUpdate>> updatesByBug = new LinkedHashMap<>();
        for (JiedanBugUpdate update : bugUpdateMapper.selectByOrderId(o.getId()))
        {
            updatesByBug.computeIfAbsent(update.getBugId(), k -> new ArrayList<>()).add(update);
        }

        List<Map<String, Object>> bugs = new ArrayList<>();
        for (JiedanBug b : bugMapper.selectByOrderId(o.getId()))
        {
            Map<String, Object> bm = new LinkedHashMap<>();
            bm.put("id", b.getId());
            bm.put("orderId", b.getOrderId());
            bm.put("content", b.getContent());
            bm.put("attachments", normalizedAttachments(b.getAttachments(), "bug", b.getId()));
            bm.put("status", normalizeBugStatus(b.getStatus()));
            bm.put("createdBy", displayMemberName(b.getCreatedBy()));
            bm.put("time", fmt(b.getCreateTime()));

            List<Map<String, Object>> updates = new ArrayList<>();
            for (JiedanBugUpdate u : updatesByBug.getOrDefault(b.getId(), new ArrayList<>()))
            {
                Map<String, Object> um = new LinkedHashMap<>();
                um.put("id", u.getId());
                um.put("bugId", u.getBugId());
                um.put("content", u.getContent());
                um.put("attachments", normalizedAttachments(u.getAttachments(), "bugUpdate", u.getId()));
                um.put("createdBy", displayMemberName(u.getCreatedBy()));
                um.put("time", fmt(u.getCreateTime()));
                updates.add(um);
            }
            bm.put("updates", updates);
            bugs.add(bm);
        }
        vo.put("bugs", bugs);

        List<Map<String, Object>> tl = new ArrayList<>();
        for (JiedanTimeline t : timelineMapper.selectByOrderId(o.getId()))
        {
            if (!includeInternal && !isChatType(t.getType())) continue;
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("id", t.getId());
            tm.put("time", fmt(t.getCreateTime()));
            tm.put("user", displayMemberName(t.getUserName()));
            tm.put("type", t.getType());
            tm.put("content", t.getContent());
            tm.put("attachments", normalizedAttachments(t.getAttachments(), "timeline", t.getId()));
            tl.add(tm);
        }
        vo.put("timeline", tl);
        return vo;
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
            last.put("type", lastType);
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
        vo.put("contact", row.get("contact"));
        vo.put("receivedAmount", asDecimal(row.get("receivedAmount")));
        vo.put("unread", parseUnread(asStr(row.get("unread"))));
        vo.put("attachmentCount", row.get("attachmentCount") == null ? 0 : row.get("attachmentCount"));
        vo.put("bugCount", row.get("bugCount") == null ? 0 : row.get("bugCount"));
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

    private Object normalizedAttachments(String json, String ownerType, Long ownerId)
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
        return name == null ? null : LEGACY_MEMBER_NAMES.getOrDefault(name, name);
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
