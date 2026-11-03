package com.ruoyi.jiedan.service;

import java.util.List;
import java.util.Map;

/**
 * 员工申请：改报价 / 取消 / 删除 / 收款这类敏感操作，员工先提交申请，老板批准后才生效。
 */
public interface IJiedanApprovalService
{
    /** 员工提交申请（orderId、type、reason、payload） */
    Map<String, Object> create(Map<String, Object> payload);

    /** status = pending 只看待批的；其余看最近的全部。老板看所有人的，员工只看自己的。 */
    List<Map<String, Object>> list(String status);

    /** 老板批准：先把申请的内容真正做掉，再标记已批准 */
    void approve(Long id, String note);

    /** 老板驳回，必须写原因 */
    void reject(Long id, String note);

    /** 申请人撤回自己还没被处理的申请 */
    void withdraw(Long id);
}
