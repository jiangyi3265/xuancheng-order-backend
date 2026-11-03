package com.ruoyi.jiedan.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
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
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.jiedan.domain.JiedanApproval;
import com.ruoyi.jiedan.domain.JiedanOrder;
import com.ruoyi.jiedan.mapper.JiedanApprovalMapper;
import com.ruoyi.jiedan.mapper.JiedanOrderMapper;
import com.ruoyi.jiedan.service.IJiedanApprovalService;
import com.ruoyi.jiedan.service.IJiedanOrderService;
import com.ruoyi.jiedan.service.JiedanAccess;
import com.ruoyi.jiedan.service.JiedanAuditService;

/**
 * 员工申请的提交、批准、驳回、撤回。
 * 批准时在老板的身份下调用订单服务把申请的内容真正做掉，做成功了才标记“已批准”，
 * 任何一步失败整件事回滚，申请仍然是“待批”。
 */
@Service
public class JiedanApprovalServiceImpl implements IJiedanApprovalService
{
    private static final BigDecimal AMOUNT_MAX = new BigDecimal("99999999.99");
    private static final List<String> TYPES = Arrays.asList(
        JiedanApproval.TYPE_AMOUNT, JiedanApproval.TYPE_CANCEL, JiedanApproval.TYPE_DELETE, JiedanApproval.TYPE_PAYMENT);

    @Autowired
    private JiedanApprovalMapper approvalMapper;

    @Autowired
    private JiedanOrderMapper orderMapper;

    @Autowired
    private IJiedanOrderService orderService;

    @Autowired
    private JiedanAccess access;

    @Autowired
    private JiedanAuditService audit;

    private static String typeLabel(String type)
    {
        switch (type)
        {
            case JiedanApproval.TYPE_AMOUNT: return "改报价";
            case JiedanApproval.TYPE_CANCEL: return "取消项目";
            case JiedanApproval.TYPE_DELETE: return "删除项目";
            case JiedanApproval.TYPE_PAYMENT: return "收款";
            default: return type;
        }
    }

    private static String statusLabel(String status)
    {
        switch (status)
        {
            case JiedanApproval.STATUS_PENDING: return "待批";
            case JiedanApproval.STATUS_APPROVED: return "已批准";
            case JiedanApproval.STATUS_REJECTED: return "已驳回";
            case JiedanApproval.STATUS_WITHDRAWN: return "已撤回";
            default: return status;
        }
    }

    // ---------- 提交 ----------
    @Override
    @Transactional
    public Map<String, Object> create(Map<String, Object> p)
    {
        if (access.isBoss()) throw new ServiceException("你是老板，可以直接操作，不需要提交申请");
        Long orderId = asLong(p.get("orderId"));
        JiedanOrder o = orderId == null ? null : orderMapper.selectById(orderId);
        if (o == null || !access.canAccess(o)) throw new ServiceException(JiedanAccess.DENIED);
        if ("pending".equals(o.getAssignState()))
        {
            throw new ServiceException("老板刚把这个项目派给你，请先点“接收”（或“拒绝”）再提交申请");
        }

        String type = p.get("type") == null ? "" : p.get("type").toString();
        if (!TYPES.contains(type)) throw new ServiceException("不认识的申请类型");
        String reason = p.get("reason") == null ? "" : p.get("reason").toString().trim();
        if (reason.isEmpty()) throw new ServiceException("请写一下申请原因");
        if (reason.length() > 500) throw new ServiceException("原因最多 500 个字");

        Map<?, ?> in = p.get("payload") instanceof Map ? (Map<?, ?>) p.get("payload") : new LinkedHashMap<>();
        Map<String, Object> payload = new LinkedHashMap<>();
        switch (type)
        {
            case JiedanApproval.TYPE_AMOUNT:
            {
                BigDecimal cur = o.getAmount() == null ? BigDecimal.ZERO : o.getAmount();
                BigDecimal want;
                try
                {
                    want = new BigDecimal(String.valueOf(in.get("amount")).trim()).setScale(2, RoundingMode.HALF_UP);
                }
                catch (Exception e)
                {
                    throw new ServiceException("新报价的金额格式不对");
                }
                if (want.signum() < 0 || want.compareTo(AMOUNT_MAX) > 0) throw new ServiceException("新报价的金额不合理");
                if (want.compareTo(cur) == 0) throw new ServiceException("新报价和现在一样，不用申请");
                payload.put("amount", want.toPlainString());
                payload.put("oldAmount", cur.setScale(2, RoundingMode.HALF_UP).toPlainString());
                break;
            }
            case JiedanApproval.TYPE_CANCEL:
                if ("done".equals(o.getStatus()) || "cancelled".equals(o.getStatus()))
                {
                    throw new ServiceException("这个项目已经" + ("done".equals(o.getStatus()) ? "完成" : "取消") + "了");
                }
                break;
            case JiedanApproval.TYPE_DELETE:
                break;
            default:
                payload.putAll(orderService.normalizePaymentInput(castMap(in)));
        }
        // 报价 / 取消 / 删除同一个项目同一类只留一条；收款可以分几笔申请
        if (!JiedanApproval.TYPE_PAYMENT.equals(type) && approvalMapper.countPending(orderId, type) > 0)
        {
            throw new ServiceException("已经有一条同样的申请在等老板批了，不用重复提交");
        }

        Date now = new Date();
        JiedanApproval a = new JiedanApproval();
        a.setOrderId(orderId);
        a.setType(type);
        a.setPayload(JSON.toJSONString(payload));
        a.setReason(reason);
        a.setStatus(JiedanApproval.STATUS_PENDING);
        a.setRequesterId(access.currentUserId());
        a.setRequesterName(access.currentName());
        a.setCreateTime(now);
        approvalMapper.insert(a);

        String what = describe(type, payload);
        audit.record("approval.request", "order", orderId, o.getTitle(), "申请" + what + "；原因：" + reason);
        orderService.flowEvent(orderId, a.getRequesterName() + " 申请" + what + "（原因：" + reason + "）",
                "📝 待批：" + typeLabel(type), o.getTitle() + "（" + a.getRequesterName() + "）：" + reason);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", a.getId());
        out.put("status", a.getStatus());
        return out;
    }

    // ---------- 列表 ----------
    @Override
    public List<Map<String, Object>> list(String status)
    {
        boolean pendingOnly = JiedanApproval.STATUS_PENDING.equals(status);
        Long scope = access.isBoss() ? null : access.currentUserId();
        List<Map<String, Object>> rows = approvalMapper.selectList(pendingOnly ? JiedanApproval.STATUS_PENDING : null,
                scope, pendingOnly ? 200 : 50);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) out.add(toVO(r));
        return out;
    }

    // ---------- 批准 ----------
    @Override
    @Transactional
    public void approve(Long id, String note)
    {
        access.requireBoss();
        JiedanApproval a = requirePending(id);
        Map<String, Object> payload = parsePayload(a.getPayload());
        Long orderId = a.getOrderId();

        // 在老板的身份下把申请的内容真正做掉；做不成功就整件事回滚，申请仍是“待批”
        switch (a.getType())
        {
            case JiedanApproval.TYPE_AMOUNT:
            {
                Map<String, Object> up = new LinkedHashMap<>();
                up.put("id", orderId);
                up.put("amount", payload.get("amount"));
                orderService.update(up);
                break;
            }
            case JiedanApproval.TYPE_CANCEL:
            {
                Map<String, Object> st = new LinkedHashMap<>();
                st.put("id", orderId);
                st.put("status", "cancelled");
                orderService.changeStatus(st);
                break;
            }
            case JiedanApproval.TYPE_DELETE:
                orderService.delete(orderId);
                break;
            default:
                orderService.addPaymentAs(orderId, payload, a.getRequesterName());
        }

        String handled = trim(note, 500);
        if (approvalMapper.markHandled(id, JiedanApproval.STATUS_APPROVED, access.currentUserId(), access.currentName(),
                handled, new Date()) == 0)
        {
            throw new ServiceException("这条申请刚刚被处理过了，请刷新看看");
        }
        String what = describe(a.getType(), payload);
        JiedanOrder o = orderMapper.selectByIdAny(orderId);
        audit.record("approval.approve", "order", orderId, o == null ? "" : o.getTitle(),
                "批准了 " + a.getRequesterName() + " 的申请：" + what);
        orderService.flowEvent(orderId, access.currentName() + " 批准了 " + a.getRequesterName() + " 的申请：" + what,
                "✅ 申请已批准", (o == null ? "" : o.getTitle()) + "：" + what);
    }

    // ---------- 驳回 ----------
    @Override
    @Transactional
    public void reject(Long id, String note)
    {
        access.requireBoss();
        JiedanApproval a = requirePending(id);
        String why = trim(note, 500);
        if (why.isEmpty()) throw new ServiceException("请写一下驳回的原因");
        if (approvalMapper.markHandled(id, JiedanApproval.STATUS_REJECTED, access.currentUserId(), access.currentName(),
                why, new Date()) == 0)
        {
            throw new ServiceException("这条申请刚刚被处理过了，请刷新看看");
        }
        String what = describe(a.getType(), parsePayload(a.getPayload()));
        JiedanOrder o = orderMapper.selectByIdAny(a.getOrderId());
        audit.record("approval.reject", "order", a.getOrderId(), o == null ? "" : o.getTitle(),
                "驳回了 " + a.getRequesterName() + " 的申请：" + what + "；原因：" + why);
        orderService.flowEvent(a.getOrderId(), access.currentName() + " 驳回了 " + a.getRequesterName() + " 的申请：" + what
                + "（原因：" + why + "）", "❌ 申请被驳回", (o == null ? "" : o.getTitle()) + "：" + why);
    }

    // ---------- 撤回 ----------
    @Override
    @Transactional
    public void withdraw(Long id)
    {
        JiedanApproval a = requirePending(id);
        if (!a.getRequesterId().equals(access.currentUserId())) throw new ServiceException("只能撤回自己提交的申请");
        if (approvalMapper.markHandled(id, JiedanApproval.STATUS_WITHDRAWN, access.currentUserId(), access.currentName(),
                "", new Date()) == 0)
        {
            throw new ServiceException("这条申请刚刚被处理过了，请刷新看看");
        }
        String what = describe(a.getType(), parsePayload(a.getPayload()));
        JiedanOrder o = orderMapper.selectByIdAny(a.getOrderId());
        audit.record("approval.withdraw", "order", a.getOrderId(), o == null ? "" : o.getTitle(),
                "撤回了申请：" + what);
        orderService.flowEvent(a.getOrderId(), a.getRequesterName() + " 撤回了申请：" + what, null, null);
    }

    // ---------- 内部辅助 ----------
    private JiedanApproval requirePending(Long id)
    {
        JiedanApproval a = id == null ? null : approvalMapper.selectById(id);
        if (a == null) throw new ServiceException("这条申请不存在");
        if (!JiedanApproval.STATUS_PENDING.equals(a.getStatus()))
        {
            throw new ServiceException("这条申请已经处理过了（" + statusLabel(a.getStatus()) + "）");
        }
        return a;
    }

    private String describe(String type, Map<String, Object> payload)
    {
        switch (type)
        {
            case JiedanApproval.TYPE_AMOUNT:
                return "改报价 ¥" + stripZero(String.valueOf(payload.get("oldAmount"))) + " → ¥"
                        + stripZero(String.valueOf(payload.get("amount")));
            case JiedanApproval.TYPE_CANCEL:
                return "取消项目";
            case JiedanApproval.TYPE_DELETE:
                return "删除项目";
            default:
            {
                Object note = payload.get("note");
                return "收款 ¥" + stripZero(String.valueOf(payload.get("amount"))) + "（" + payload.get("payDate")
                        + (note == null || note.toString().isEmpty() ? "" : "，" + note) + "）";
            }
        }
    }

    private Map<String, Object> toVO(Map<String, Object> r)
    {
        Map<String, Object> vo = new LinkedHashMap<>(r);
        String type = String.valueOf(r.get("type"));
        String status = String.valueOf(r.get("status"));
        Map<String, Object> payload = parsePayload(r.get("payload") == null ? "" : r.get("payload").toString());
        vo.put("payload", payload);
        vo.put("typeLabel", typeLabel(type));
        vo.put("statusLabel", statusLabel(status));
        vo.put("summary", describe(type, payload));
        vo.put("createTime", fmt(r.get("createTime")));
        vo.put("handleTime", fmt(r.get("handleTime")));
        return vo;
    }

    private Map<String, Object> parsePayload(String json)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        if (json == null || json.trim().isEmpty()) return out;
        try
        {
            out.putAll(JSON.parseObject(json));
        }
        catch (Exception ignore)
        {
            // 内容坏了就当没有，不影响列表
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Map<?, ?> m)
    {
        return (Map<String, Object>) m;
    }

    private String fmt(Object value)
    {
        if (value == null) return "";
        if (value instanceof Date) return new SimpleDateFormat("yyyy-MM-dd HH:mm").format((Date) value);
        if (value instanceof java.time.LocalDateTime)
        {
            return ((java.time.LocalDateTime) value).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        }
        return value.toString();
    }

    private String stripZero(String s)
    {
        try
        {
            return new BigDecimal(s).stripTrailingZeros().toPlainString();
        }
        catch (Exception e)
        {
            return s;
        }
    }

    private String trim(String s, int max)
    {
        String t = s == null ? "" : s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private Long asLong(Object o)
    {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(o.toString().trim()); } catch (Exception e) { return null; }
    }
}
