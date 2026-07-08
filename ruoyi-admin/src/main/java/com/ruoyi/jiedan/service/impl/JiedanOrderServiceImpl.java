package com.ruoyi.jiedan.service.impl;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.jiedan.domain.JiedanOrder;
import com.ruoyi.jiedan.domain.JiedanSetting;
import com.ruoyi.jiedan.domain.JiedanTimeline;
import com.ruoyi.jiedan.mapper.JiedanOrderMapper;
import com.ruoyi.jiedan.mapper.JiedanSettingMapper;
import com.ruoyi.jiedan.mapper.JiedanTimelineMapper;
import com.ruoyi.jiedan.service.IJiedanOrderService;
import com.ruoyi.jiedan.service.JiedanPushService;

@Service
public class JiedanOrderServiceImpl implements IJiedanOrderService
{
    @Autowired
    private JiedanOrderMapper orderMapper;

    @Autowired
    private JiedanTimelineMapper timelineMapper;

    @Autowired
    private JiedanSettingMapper settingMapper;

    @Autowired
    private JiedanPushService pushService;

    private static final List<Long> MEMBER_IDS = Arrays.asList(1L, 2L);

    private static final Map<Long, String> MEMBER_NAMES = new LinkedHashMap<Long, String>()
    {{
        put(1L, "阿成");
        put(2L, "阿伟");
    }};

    private static final Map<String, String> STATUS_LABELS = new LinkedHashMap<String, String>()
    {{
        put("pending", "待确认");
        put("doing", "进行中");
        put("review", "待验收");
        put("done", "已完成");
        put("cancelled", "已取消");
    }};

    // ---------- 查询 ----------
    @Override
    public List<Map<String, Object>> listVO()
    {
        List<Map<String, Object>> list = new ArrayList<>();
        for (JiedanOrder o : orderMapper.selectAll())
        {
            list.add(toVO(o));
        }
        return list;
    }

    @Override
    public Map<String, Object> getVO(Long id)
    {
        JiedanOrder o = orderMapper.selectById(id);
        return o == null ? null : toVO(o);
    }

    // ---------- 客户门户：仅限本人项目 ----------
    @Override
    public List<Map<String, Object>> listByCustomer(String account)
    {
        List<Map<String, Object>> list = new ArrayList<>();
        if (account == null || account.trim().isEmpty()) return list;
        for (JiedanOrder o : orderMapper.selectByCustomerAccount(account))
        {
            list.add(toVO(o));
        }
        return list;
    }

    @Override
    public Map<String, Object> getForCustomer(Long id, String account)
    {
        JiedanOrder o = orderMapper.selectById(id);
        if (o == null || account == null || !account.equals(o.getCustomerAccount())) return null;
        return toVO(o);
    }

    // ---------- 客户留言：老板与员工同时收到并标记未读 ----------
    @Override
    public Map<String, Object> customerMessage(Long orderId, String account, String customerName,
                                               String content, Object attachments)
    {
        JiedanOrder o = orderMapper.selectById(orderId);
        if (o == null || account == null || !account.equals(o.getCustomerAccount())) return null;
        Date now = new Date();
        String name = strOr(customerName, strOr(o.getCustomer(), "客户"));
        addTimeline(orderId, name, "message", content, jsonStr(attachments), now);
        // 客户消息：老板和员工都置为未读
        JiedanOrder upd = new JiedanOrder();
        upd.setId(orderId);
        upd.setUnread(join(MEMBER_IDS));
        upd.setUpdateTime(now);
        orderMapper.update(upd);
        // 推送给全部成员
        String brief = strOr(content, "[消息]");
        for (Long m : MEMBER_IDS)
        {
            pushService.pushToMember(m, "💬 客户留言", o.getTitle() + "：" + brief + "（" + name + "）");
        }
        return getForCustomer(orderId, account);
    }

    // ---------- 新增（建单即派单 + 推送） ----------
    @Override
    @Transactional
    public Map<String, Object> create(Map<String, Object> p)
    {
        Date now = new Date();
        Long by = asLong(p.get("byMemberId"));
        String byName = strOr(asStr(p.get("byUserName")), MEMBER_NAMES.getOrDefault(by, "系统"));

        JiedanOrder o = new JiedanOrder();
        o.setTitle(strOr(asStr(p.get("title")), "未命名需求"));
        o.setChannel(strOr(asStr(p.get("channel")), "wechat"));
        o.setCustomer(asStr(p.get("customer")));
        o.setCustomerAccount(asStr(p.get("customerAccount")));
        o.setContact(asStr(p.get("contact")));
        o.setAmount(asDecimal(p.get("amount")));
        o.setOwnerId(asLong(p.get("ownerId")));
        o.setStatus(strOr(asStr(p.get("status")), "pending"));
        o.setPriority(strOr(asStr(p.get("priority")), "medium"));
        o.setDeadline(asStr(p.get("deadline")));
        o.setRequirement(asStr(p.get("requirement")));
        o.setAttachments(jsonStr(p.get("attachments")));
        o.setUnread(csvOthers(by));
        o.setPaid(0);
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
        pushOthers(by, "🆕 新单待接", o.getTitle() + dash(o.getCustomer()) + "（来自 " + byName + "）");
        return getVO(o.getId());
    }

    // ---------- 修改 ----------
    @Override
    public Map<String, Object> update(Map<String, Object> p)
    {
        Long id = asLong(p.get("id"));
        Long by = asLong(p.get("byMemberId"));
        JiedanOrder o = new JiedanOrder();
        o.setId(id);
        if (p.containsKey("title")) o.setTitle(asStr(p.get("title")));
        if (p.containsKey("channel")) o.setChannel(asStr(p.get("channel")));
        if (p.containsKey("customer")) o.setCustomer(asStr(p.get("customer")));
        if (p.containsKey("customerAccount")) o.setCustomerAccount(asStr(p.get("customerAccount")));
        if (p.containsKey("contact")) o.setContact(asStr(p.get("contact")));
        if (p.containsKey("amount")) o.setAmount(asDecimal(p.get("amount")));
        if (p.containsKey("ownerId")) o.setOwnerId(asLong(p.get("ownerId")));
        if (p.containsKey("status")) o.setStatus(asStr(p.get("status")));
        if (p.containsKey("priority")) o.setPriority(asStr(p.get("priority")));
        if (p.containsKey("deadline")) o.setDeadline(asStr(p.get("deadline")));
        if (p.containsKey("requirement")) o.setRequirement(asStr(p.get("requirement")));
        if (p.containsKey("attachments")) o.setAttachments(jsonStr(p.get("attachments")));
        if (p.containsKey("paid")) o.setPaid(asBool(p.get("paid")) ? 1 : 0);
        if (by != null) o.setUnread(csvOthers(by));
        o.setUpdateTime(new Date());
        orderMapper.update(o);
        return getVO(id);
    }

    // ---------- 删除 ----------
    @Override
    @Transactional
    public void delete(Long id)
    {
        timelineMapper.deleteByOrderId(id);
        orderMapper.deleteById(id);
    }

    // ---------- 项目记事本（内部台账） ----------
    @Override
    public Map<String, Object> getNotes(Long id)
    {
        JiedanOrder o = orderMapper.selectNotesById(id);
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", id);
        vo.put("notes", o == null ? "" : strOr(o.getNotes(), ""));
        vo.put("noteAttachments", o == null ? new ArrayList<>() : parseArr(o.getNoteAttachments()));
        return vo;
    }

    @Override
    public Map<String, Object> saveNotes(Map<String, Object> p)
    {
        Long id = asLong(p.get("id"));
        if (id == null) return null;
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
        Long orderId = asLong(p.get("orderId"));
        Long by = asLong(p.get("byMemberId"));
        String byName = strOr(asStr(p.get("byUserName")), MEMBER_NAMES.getOrDefault(by, "系统"));
        String type = strOr(asStr(p.get("type")), "note");
        Date now = new Date();
        addTimeline(orderId, byName, type, asStr(p.get("content")), jsonStr(p.get("attachments")), now);
        touchUnread(orderId, by, now);
        JiedanOrder o = orderMapper.selectById(orderId);
        if (o != null) pushOthers(by, "💬 新动态", o.getTitle() + "：" + strOr(asStr(p.get("content")), "有新进度") + "（" + byName + "）");
        return getVO(orderId);
    }

    // ---------- 改状态 ----------
    @Override
    public Map<String, Object> changeStatus(Map<String, Object> p)
    {
        Long id = asLong(p.get("id"));
        Long by = asLong(p.get("byMemberId"));
        String byName = strOr(asStr(p.get("byUserName")), MEMBER_NAMES.getOrDefault(by, "系统"));
        String status = asStr(p.get("status"));
        Date now = new Date();

        JiedanOrder upd = new JiedanOrder();
        upd.setId(id);
        upd.setStatus(status);
        upd.setUpdateTime(now);
        orderMapper.update(upd);

        String label = STATUS_LABELS.getOrDefault(status, status);
        addTimeline(id, byName, "status", "状态更新为「" + label + "」", null, now);
        touchUnread(id, by, now);
        JiedanOrder o = orderMapper.selectById(id);
        if (o != null) pushOthers(by, "🔔 " + label, o.getTitle() + "（" + byName + "）");
        return getVO(id);
    }

    // ---------- 标记已读 ----------
    @Override
    public Map<String, Object> markRead(Long id, Long memberId)
    {
        JiedanOrder o = orderMapper.selectById(id);
        if (o != null && memberId != null)
        {
            List<Long> unread = parseUnread(o.getUnread());
            unread.remove(memberId);
            JiedanOrder upd = new JiedanOrder();
            upd.setId(id);
            upd.setUnread(join(unread));
            orderMapper.update(upd);
        }
        return getVO(id);
    }

    // ---------- 改稿 +1 ----------
    @Override
    public Map<String, Object> addRevision(Map<String, Object> p)
    {
        Long id = asLong(p.get("id"));
        Long by = asLong(p.get("byMemberId"));
        String byName = strOr(asStr(p.get("byUserName")), MEMBER_NAMES.getOrDefault(by, "系统"));
        Date now = new Date();
        JiedanOrder o = orderMapper.selectById(id);
        if (o == null) return null;
        int n = (o.getRevisions() == null ? 0 : o.getRevisions()) + 1;
        JiedanOrder upd = new JiedanOrder();
        upd.setId(id);
        upd.setRevisions(n);
        upd.setUpdateTime(now);
        orderMapper.update(upd);
        addTimeline(id, byName, "note", "客户第 " + n + " 次改稿", null, now);
        touchUnread(id, by, now);
        pushOthers(by, "✏️ 客户改稿", o.getTitle() + " 第 " + n + " 次改稿");
        return getVO(id);
    }

    // ---------- 催一下 ----------
    @Override
    public Map<String, Object> nudge(Map<String, Object> p)
    {
        Long id = asLong(p.get("id"));
        Long by = asLong(p.get("byMemberId"));
        String byName = strOr(asStr(p.get("byUserName")), MEMBER_NAMES.getOrDefault(by, "系统"));
        Date now = new Date();
        JiedanOrder o = orderMapper.selectById(id);
        if (o == null) return null;
        // 催谁：优先催负责人；若自己就是负责人则催另一位
        Long target = o.getOwnerId();
        if (target == null || target.equals(by))
        {
            target = MEMBER_IDS.stream().filter(m -> !m.equals(by)).findFirst().orElse(null);
        }
        addTimeline(id, byName, "note", byName + " 催了一下：" + o.getTitle(), null, now);
        // 催办标记为目标未读
        if (target != null)
        {
            List<Long> unread = parseUnread(o.getUnread());
            if (!unread.contains(target)) unread.add(target);
            JiedanOrder upd = new JiedanOrder();
            upd.setId(id);
            upd.setUnread(join(unread));
            upd.setUpdateTime(now);
            orderMapper.update(upd);
            pushService.pushToMember(target, "⏰ 催办", o.getTitle() + " —— " + byName + " 催你跟进");
        }
        return getVO(id);
    }

    // ---------- 推送设置 ----------
    @Override
    public Map<String, String> getPushConfig()
    {
        Map<String, String> cfg = new LinkedHashMap<>();
        for (JiedanSetting s : settingMapper.selectAll())
        {
            cfg.put(s.getSettingKey(), s.getSettingValue());
        }
        return cfg;
    }

    @Override
    public void savePushConfig(Map<String, Object> p)
    {
        for (Map.Entry<String, Object> e : p.entrySet())
        {
            if (e.getKey() != null && e.getKey().startsWith("push."))
            {
                settingMapper.upsert(e.getKey(), e.getValue() == null ? "" : e.getValue().toString());
            }
        }
    }

    @Override
    public String testPush(Long memberId)
    {
        return pushService.testPush(memberId);
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

    // ================= 内部辅助 =================
    private void pushOthers(Long by, String title, String content)
    {
        for (Long m : MEMBER_IDS)
        {
            if (!m.equals(by)) pushService.pushToMember(m, title, content);
        }
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

    private void touchUnread(Long orderId, Long by, Date now)
    {
        JiedanOrder upd = new JiedanOrder();
        upd.setId(orderId);
        upd.setUnread(csvOthers(by));
        upd.setUpdateTime(now);
        orderMapper.update(upd);
    }

    private Map<String, Object> toVO(JiedanOrder o)
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
        vo.put("createTime", fmt(o.getCreateTime()));
        vo.put("attachments", parseArr(o.getAttachments()));
        vo.put("unread", parseUnread(o.getUnread()));

        List<Map<String, Object>> tl = new ArrayList<>();
        for (JiedanTimeline t : timelineMapper.selectByOrderId(o.getId()))
        {
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("time", fmt(t.getCreateTime()));
            tm.put("user", t.getUserName());
            tm.put("type", t.getType());
            tm.put("content", t.getContent());
            tm.put("attachments", parseArr(t.getAttachments()));
            tl.add(tm);
        }
        vo.put("timeline", tl);
        return vo;
    }

    private String dash(String s)
    {
        return (s == null || s.trim().isEmpty()) ? "" : " · " + s;
    }

    private String csvOthers(Long by)
    {
        List<String> others = new ArrayList<>();
        for (Long m : MEMBER_IDS)
        {
            if (!m.equals(by)) others.add(String.valueOf(m));
        }
        return String.join(",", others);
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

    private Object parseArr(String json)
    {
        if (json == null || json.trim().isEmpty()) return new ArrayList<>();
        try { return JSON.parseArray(json); } catch (Exception e) { return new ArrayList<>(); }
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
