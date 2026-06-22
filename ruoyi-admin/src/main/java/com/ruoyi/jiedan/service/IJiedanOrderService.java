package com.ruoyi.jiedan.service;

import java.util.List;
import java.util.Map;

public interface IJiedanOrderService
{
    List<Map<String, Object>> listVO();

    Map<String, Object> getVO(Long id);

    Map<String, Object> create(Map<String, Object> payload);

    Map<String, Object> update(Map<String, Object> payload);

    void delete(Long id);

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
