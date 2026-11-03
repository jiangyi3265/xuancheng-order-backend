package com.ruoyi.jiedan.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.ruoyi.jiedan.domain.JiedanApproval;
import com.ruoyi.jiedan.domain.JiedanOrder;
import com.ruoyi.jiedan.mapper.JiedanAccountMapper;
import com.ruoyi.jiedan.mapper.JiedanApprovalMapper;
import com.ruoyi.jiedan.mapper.JiedanOrderMapper;

/**
 * 每日汇总提醒：每天早上把"还有什么没处理"整理成一条微信消息，发给每个老板和每个在用的员工。
 * 只读数据，不改任何东西；真正发送交给 JiedanPushService（没配 SendKey 或推送关闭时不会发出）。
 */
@Service
public class JiedanDigestService
{
    private static final Logger log = LoggerFactory.getLogger(JiedanDigestService.class);

    public static final String TITLE = "📋 今日汇总";
    private static final String ROLE_BOSS = "boss";
    private static final String ROLE_EMPLOYEE = "employee";
    /** 待批申请最多读多少条（远大于实际会有的数量，只为防止极端情况下一次读太多） */
    private static final int APPROVAL_LIMIT = 5000;
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /**
     * 库里的时间一律按连接串 serverTimezone=GMT+8 写入；查询结果里的 LocalDateTime 只是这个时区的"墙上时间"，
     * 要按它换算成时间点，才不会受服务器自己所在时区影响。
     */
    private static final ZoneId DB_ZONE = ZoneId.of("GMT+8");

    @Autowired
    private JiedanOrderMapper orderMapper;

    @Autowired
    private JiedanApprovalMapper approvalMapper;

    @Autowired
    private JiedanAccountMapper accountMapper;

    @Autowired
    private JiedanPushService pushService;

    /** 一组项目计数（全局一份给老板，每个负责人一份给员工） */
    private static class Tally
    {
        int toAccept;
        int overdue;
        int noDeadline;
        int review;
        int owedCount;
        BigDecimal owedSum = BigDecimal.ZERO;
    }

    /** 申请计数（全局一份给老板，每个申请人一份给员工） */
    private static class ApprovalTally
    {
        int count;
        Instant oldest;

        void add(Instant createTime)
        {
            count++;
            if (createTime != null && (oldest == null || createTime.isBefore(oldest))) oldest = createTime;
        }
    }

    /**
     * 为每个老板和每个在用（未停用）的员工生成一条汇总：{memberId, name, role, title, body}。
     * 没有任何要说的人不出现在结果里。只读，不发送。
     */
    public List<Map<String, Object>> build()
    {
        Instant now = Instant.now();
        String today = LocalDate.now().toString();

        // ---- 项目：一次读出，按负责人分组计数 ----
        Tally all = new Tally();
        Map<Long, Tally> byOwner = new HashMap<>();
        for (JiedanOrder o : orderMapper.selectAll())
        {
            String st = o.getStatus();
            if (st == null || "cancelled".equals(st)) continue; // 已取消的项目哪里都不算
            Tally mine = o.getOwnerId() == null ? null : byOwner.computeIfAbsent(o.getOwnerId(), k -> new Tally());
            String dl = o.getDeadline() == null ? "" : o.getDeadline().trim();

            if ("pending".equals(o.getAssignState()))
            {
                all.toAccept++;
                if (mine != null) mine.toAccept++;
            }
            boolean open = "pending".equals(st) || "doing".equals(st) || "review".equals(st);
            if (open && !dl.isEmpty() && dl.compareTo(today) < 0)
            {
                all.overdue++;
                if (mine != null) mine.overdue++;
            }
            if ("doing".equals(st) && dl.isEmpty())
            {
                all.noDeadline++;
                if (mine != null) mine.noDeadline++;
            }
            if ("review".equals(st)) all.review++;
            if ("done".equals(st))
            {
                BigDecimal amount = o.getAmount() == null ? BigDecimal.ZERO : o.getAmount();
                BigDecimal received = o.getReceivedAmount() == null ? BigDecimal.ZERO : o.getReceivedAmount();
                if (amount.compareTo(received) > 0)
                {
                    all.owedCount++;
                    all.owedSum = all.owedSum.add(amount.subtract(received));
                }
            }
        }

        // ---- 待批申请：一次读出，按申请人分组 ----
        ApprovalTally allApprovals = new ApprovalTally();
        Map<Long, ApprovalTally> approvalsByRequester = new HashMap<>();
        List<Map<String, Object>> rows = approvalMapper.selectList(JiedanApproval.STATUS_PENDING, null, APPROVAL_LIMIT);
        for (Map<String, Object> r : rows)
        {
            Instant created = toInstant(r.get("createTime"));
            allApprovals.add(created);
            Long requester = toLong(r.get("requesterId"));
            if (requester != null) approvalsByRequester.computeIfAbsent(requester, k -> new ApprovalTally()).add(created);
        }

        // ---- 账号：老板 + 在用的员工 ----
        Set<Long> bossIds = new HashSet<>(accountMapper.selectBossUserIds());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : accountMapper.selectStaff(null))
        {
            if (!"0".equals(String.valueOf(row.get("status")))) continue; // 停用的账号不打扰
            Long id = toLong(row.get("userId"));
            if (id == null) continue;
            boolean boss = bossIds.contains(id);

            List<String> lines = boss
                    ? bossLines(all, allApprovals, now)
                    : employeeLines(byOwner.get(id), approvalsByRequester.get(id));
            if (lines.isEmpty()) continue;

            Map<String, Object> d = new LinkedHashMap<>();
            d.put("memberId", id);
            d.put("name", nameOf(row));
            d.put("role", boss ? ROLE_BOSS : ROLE_EMPLOYEE);
            d.put("title", TITLE);
            d.put("body", String.join("\n", lines));
            out.add(d);
        }
        return out;
    }

    /**
     * 生成汇总并逐个推送，返回交给微信推送的条数（没配 SendKey 或推送关闭的人，推送服务会直接跳过）。
     */
    public int run()
    {
        int n = 0;
        for (Map<String, Object> d : build())
        {
            Long memberId = (Long) d.get("memberId");
            // Server酱按 Markdown 显示，单个换行会被并成一行，所以推送时每行之间空一行
            String body = String.valueOf(d.get("body")).replace("\n", "\n\n");
            try
            {
                pushService.pushToMember(memberId, String.valueOf(d.get("title")), body);
                n++;
            }
            catch (Exception e)
            {
                log.warn("[接单] 每日汇总推送失败 member={}: {}", memberId, e.getMessage());
            }
        }
        return n;
    }

    // ================= 文案 =================

    private List<String> bossLines(Tally t, ApprovalTally approvals, Instant now)
    {
        List<String> lines = new ArrayList<>();
        if (approvals.count > 0)
        {
            String wait = "";
            if (approvals.oldest != null)
            {
                long days = Math.max(0, Duration.between(approvals.oldest, now).toDays());
                wait = days >= 1 ? "（最久的已等 " + days + " 天）" : "（最久的等了不到 1 天）";
            }
            lines.add(approvals.count + " 条申请等你批准" + wait);
        }
        if (t.toAccept > 0) lines.add(t.toAccept + " 个项目等员工接收");
        if (t.review > 0) lines.add(t.review + " 个项目待验收");
        if (t.overdue > 0) lines.add(t.overdue + " 个项目已逾期");
        if (t.owedCount > 0) lines.add(t.owedCount + " 个已完成的项目还没收齐款，共欠 ¥" + money(t.owedSum));
        if (t.noDeadline > 0) lines.add(t.noDeadline + " 个进行中的项目没填截止日期");
        return lines;
    }

    private List<String> employeeLines(Tally t, ApprovalTally approvals)
    {
        List<String> lines = new ArrayList<>();
        if (t != null)
        {
            if (t.toAccept > 0) lines.add(t.toAccept + " 个项目等你接收");
            if (t.overdue > 0) lines.add(t.overdue + " 个项目已逾期");
            if (t.noDeadline > 0) lines.add(t.noDeadline + " 个进行中的项目没填截止日期");
        }
        if (approvals != null && approvals.count > 0) lines.add("你有 " + approvals.count + " 条申请还在等老板批");
        return lines;
    }

    /** 金额：整数不带小数，有小数保留两位 */
    private String money(BigDecimal v)
    {
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() <= 0 ? s.toPlainString() : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String nameOf(Map<String, Object> row)
    {
        Object nick = row.get("nickName");
        if (nick != null && !nick.toString().trim().isEmpty()) return nick.toString().trim();
        Object user = row.get("userName");
        return user == null ? "" : user.toString();
    }

    // ================= 取值转换 =================

    private Long toLong(Object v)
    {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v == null) return null;
        try
        {
            return Long.valueOf(v.toString().trim());
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    /** 数据库行里的时间可能是 java.util.Date（含 Timestamp）、LocalDateTime 或文本，统一成时间点；认不出来返回 null */
    private Instant toInstant(Object v)
    {
        if (v == null) return null;
        if (v instanceof Date) return Instant.ofEpochMilli(((Date) v).getTime());
        if (v instanceof LocalDateTime) return ((LocalDateTime) v).atZone(DB_ZONE).toInstant();
        if (v instanceof java.time.OffsetDateTime) return ((java.time.OffsetDateTime) v).toInstant();
        if (v instanceof LocalDate) return ((LocalDate) v).atStartOfDay(DB_ZONE).toInstant();
        try
        {
            String s = v.toString().trim();
            if (s.length() >= 19) return LocalDateTime.parse(s.substring(0, 19).replace('T', ' '), DATE_TIME).atZone(DB_ZONE).toInstant();
            if (s.length() == 10) return LocalDate.parse(s).atStartOfDay(DB_ZONE).toInstant();
        }
        catch (Exception ignore)
        {
            // 认不出来就当没有时间，只是汇总里少一句"最久的已等 X 天"
        }
        return null;
    }
}
