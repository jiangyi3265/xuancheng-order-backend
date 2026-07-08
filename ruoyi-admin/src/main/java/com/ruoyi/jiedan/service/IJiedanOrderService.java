package com.ruoyi.jiedan.service;

import java.util.List;
import java.util.Map;

public interface IJiedanOrderService
{
    List<Map<String, Object>> listVO();

    Map<String, Object> getVO(Long id);

    /** 客户门户：列出某客户账号名下的项目 */
    List<Map<String, Object>> listByCustomer(String account);

    /** 客户门户：查看单个项目（校验归属，非本人返回 null） */
    Map<String, Object> getForCustomer(Long id, String account);

    /** 客户门户：客户留言（校验归属；老板+员工同时未读并推送） */
    Map<String, Object> customerMessage(Long orderId, String account, String customerName,
                                        String content, Object attachments);

    Map<String, Object> create(Map<String, Object> payload);

    Map<String, Object> update(Map<String, Object> payload);

    void delete(Long id);

    boolean deleteForCustomer(Long id, String account);

    Map<String, Object> createBug(Map<String, Object> payload);

    Map<String, Object> deleteBug(Long bugId);

    Map<String, Object> addBugUpdate(Long bugId, Map<String, Object> payload);

    Map<String, Object> createBugForCustomer(Map<String, Object> payload, String account, String customerName);

    Map<String, Object> deleteBugForCustomer(Long bugId, String account);

    Map<String, Object> addBugUpdateForCustomer(Long bugId, Map<String, Object> payload, String account, String customerName);

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
