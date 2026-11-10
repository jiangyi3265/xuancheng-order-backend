package com.ruoyi.jiedan.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.jiedan.domain.JiedanOrder;

public interface JiedanOrderMapper
{
    List<JiedanOrder> selectAll();

    /** ownerId 不为空时只返回负责人是该账号的项目；为空返回全部（总管理员） */
    List<Map<String, Object>> selectAdminSummaries(@Param("ownerId") Long ownerId, @Param("me") Long me);

    int updateNextStep(@Param("id") Long id, @Param("nextStep") String nextStep, @Param("nextOwnerId") Long nextOwnerId,
                       @Param("nextDue") String nextDue, @Param("updateTime") java.util.Date updateTime);

    JiedanOrder selectScopedForUpdate(@Param("id") Long id, @Param("ownerScope") Long ownerScope);

    int updateUrgentUntil(@Param("id") Long id, @Param("urgentUntil") String urgentUntil);

    int updateHandling(@Param("id") Long id, @Param("handled") boolean handled, @Param("time") java.util.Date time);

    void beginReceipt(@Param("id") Long id, @Param("key") String key, @Param("recipient") Long recipient, @Param("entry") Long entry);

    int confirmReceipt(@Param("id") Long id, @Param("key") String key, @Param("entry") Long entry);

    void reserveIntake(@Param("customerId") Long customerId, @Param("key") String key, @Param("hash") String hash);

    Map<String, Object> lockIntake(@Param("customerId") Long customerId, @Param("key") String key, @Param("account") String account);

    void finishIntake(@Param("customerId") Long customerId, @Param("key") String key,
                     @Param("orderId") Long orderId, @Param("bugId") Long bugId);

    List<Long> customerProjectIds(@Param("account") String account);

    String selectReceipts(@Param("id") Long id);

    void clearReceipts(@Param("id") Long id, @Param("key") String key);

    void clearReassignedReceipts(@Param("id") Long id);

    int bumpVersion(@Param("id") Long id);

    List<JiedanOrder> selectByCustomerAccount(String customerAccount);

    List<java.util.Map<String, Object>> selectCustomerSummaries(String customerAccount);

    Long selectVersionById(Long id);

    /** 同 selectVersionById，但 ownerId 不为空时要求项目负责人是该账号，否则返回 null */
    Long selectVersionScoped(@Param("id") Long id, @Param("ownerId") Long ownerId);

    Long selectVersionForCustomer(@Param("id") Long id, @Param("customerAccount") String customerAccount);

    /** 只查未删除的项目；回收站里的项目返回 null */
    JiedanOrder selectById(Long id);

    /** 含回收站里的项目，仅供回收站的恢复 / 彻底删除使用 */
    JiedanOrder selectByIdAny(Long id);

    /** 回收站列表；ownerId 不为空时只返回负责人是该账号的项目 */
    List<Map<String, Object>> selectTrash(@Param("ownerId") Long ownerId);

    /** 变更戳：项目数-版本和-最大 id，任何增删改都会让它变化 */
    String selectStamp(@Param("ownerId") Long ownerId);

    JiedanOrder selectNotesById(Long id);

    int insert(JiedanOrder order);

    int update(JiedanOrder order);

    int updateAttachmentsOnly(@Param("id") Long id, @Param("attachments") String attachments);

    int updateNoteAttachmentsOnly(@Param("id") Long id, @Param("attachments") String attachments);

    /** 放进回收站 */
    int softDelete(@Param("id") Long id, @Param("by") String by, @Param("byId") Long byId, @Param("time") java.util.Date time);

    /** 从回收站恢复；newOwnerId 不为空时同时改派负责人 */
    int restore(@Param("id") Long id, @Param("newOwnerId") Long newOwnerId, @Param("time") java.util.Date time);

    /** 物理删除（仅限回收站里的项目） */
    int deleteById(Long id);
}
