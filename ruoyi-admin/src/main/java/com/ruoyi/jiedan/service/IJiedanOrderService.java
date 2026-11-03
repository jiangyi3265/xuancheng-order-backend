package com.ruoyi.jiedan.service;

import java.util.List;
import java.util.Map;

public interface IJiedanOrderService
{
    List<Map<String, Object>> listVO();

    Map<String, Object> getVO(Long id);

    Map<String, Object> getVersion(Long id);

    /** 变更戳：我能看到的项目只要有任何增删改，它就会变；后台用它决定要不要重新拉列表 */
    String stamp();

    /** 在“我能看到的项目”里按条件筛选（关键字/渠道/负责人/状态/收款/逾期/日期），导出用 */
    List<Map<String, Object>> listFiltered(Map<String, String> filters);

    /** 记一笔收款（金额 / 日期 / 备注），返回更新后的项目详情 */
    Map<String, Object> addPayment(Long orderId, Map<String, Object> payload);

    /** 记收款；createdBy 为空表示当前登录人（批准员工的收款申请时记在申请人名下） */
    Map<String, Object> addPaymentAs(Long orderId, Map<String, Object> payload, String createdBy);

    /** 校验并整理一笔收款的内容，返回可存储的规范写法；不合法抛 ServiceException */
    Map<String, Object> normalizePaymentInput(Map<String, Object> payload);

    /** 员工接收老板派来的项目 */
    Map<String, Object> accept(Long id);

    /** 员工拒绝刚派来的项目（必须写原因），项目回到老板名下 */
    Map<String, Object> reject(Long id, String reason);

    /** 员工把项目交回老板（必须写原因），项目回到老板名下并变成待确认 */
    Map<String, Object> handback(Long id, String reason);

    /** 老板验收不通过：退回返工（必须写原因），回到进行中、改稿 +1 */
    Map<String, Object> returnForRework(Long id, String reason);

    /** 给申请流程用：记时间线、标未读、推送 */
    void flowEvent(Long orderId, String text, String pushTitle, String pushBody);

    /** 删除一笔收款，返回更新后的项目详情 */
    Map<String, Object> deletePayment(Long paymentId);

    /** 实收统计：本月、累计、最近 6 个月逐月（只含我能看到的项目） */
    Map<String, Object> income();

    /** 回收站列表：总管理员看全部，下属管理员只看自己名下 */
    List<Map<String, Object>> listTrash();

    /** 从回收站恢复 */
    void restore(Long id);

    /** 彻底删除回收站里的项目（仅总管理员） */
    void purge(Long id);

    /** 客户门户：列出某客户账号名下的项目 */
    List<Map<String, Object>> listByCustomer(String account);

    /** 客户门户：查看单个项目（校验归属，非本人返回 null） */
    Map<String, Object> getForCustomer(Long id, String account);

    Map<String, Object> getVersionForCustomer(Long id, String account);

    /** 客户门户：客户留言（校验归属；老板+员工同时未读并推送） */
    Map<String, Object> customerMessage(Long orderId, String account, String customerName,
                                        String content, Object attachments);

    /** 管理端建单：下属管理员只能建在自己名下，总管理员可指派负责人 */
    Map<String, Object> create(Map<String, Object> payload);

    /** 客户端提交需求：自动落到该客户归属管理员的名下 */
    Map<String, Object> createFromCustomer(Map<String, Object> payload, String account);

    Map<String, Object> update(Map<String, Object> payload);

    /** 删除 = 放进回收站，可恢复 */
    void delete(Long id);

    /** 客户撤回自己提交的问题：仅限还在“待确认”的项目，其余抛 ServiceException；不存在/不是本人的返回 false */
    boolean deleteForCustomer(Long id, String account);

    Map<String, Object> createBug(Map<String, Object> payload);

    Map<String, Object> deleteBug(Long bugId);

    Map<String, Object> addBugUpdate(Long bugId, Map<String, Object> payload);

    Map<String, Object> changeBugStatus(Long bugId, Map<String, Object> payload);

    Map<String, Object> createBugForCustomer(Map<String, Object> payload, String account, String customerName);

    Map<String, Object> deleteBugForCustomer(Long bugId, String account);

    Map<String, Object> addBugUpdateForCustomer(Long bugId, Map<String, Object> payload, String account, String customerName);

    Map<String, Object> changeBugStatusForCustomer(Long bugId, Map<String, Object> payload, String account, String customerName);

    /** 项目记事本：读取（内部使用） */
    Map<String, Object> getNotes(Long id);

    /** 项目记事本：保存（内部使用） */
    Map<String, Object> saveNotes(Map<String, Object> payload);

    Map<String, Object> addProgress(Map<String, Object> payload);

    Map<String, Object> changeStatus(Map<String, Object> payload);

    Map<String, Object> markRead(Long id, Long memberId);

    /** 改稿 +1 */
    Map<String, Object> addRevision(Map<String, Object> payload);

    /** 催一下（推送对方 + 记时间线） */
    Map<String, Object> nudge(Map<String, Object> payload);

    /** 推送设置：读取 */
    Map<String, String> getPushConfig();

    /** 推送设置：保存 */
    void savePushConfig(Map<String, Object> payload);

    /** 发送测试推送 */
    String testPush(Long memberId);

    /** 定时：逾期/临近截止提醒，返回推送条数 */
    int runDeadlineReminder();
}
