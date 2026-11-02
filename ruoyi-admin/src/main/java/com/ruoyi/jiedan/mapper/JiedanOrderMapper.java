package com.ruoyi.jiedan.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.jiedan.domain.JiedanOrder;

public interface JiedanOrderMapper
{
    List<JiedanOrder> selectAll();

    /** ownerId 不为空时只返回负责人是该账号的项目；为空返回全部（总管理员） */
    List<Map<String, Object>> selectAdminSummaries(@Param("ownerId") Long ownerId);

    List<JiedanOrder> selectByCustomerAccount(String customerAccount);

    List<java.util.Map<String, Object>> selectCustomerSummaries(String customerAccount);

    Long selectVersionById(Long id);

    /** 同 selectVersionById，但 ownerId 不为空时要求项目负责人是该账号，否则返回 null */
    Long selectVersionScoped(@Param("id") Long id, @Param("ownerId") Long ownerId);

    Long selectVersionForCustomer(@Param("id") Long id, @Param("customerAccount") String customerAccount);

    JiedanOrder selectById(Long id);

    JiedanOrder selectNotesById(Long id);

    int insert(JiedanOrder order);

    int update(JiedanOrder order);

    int updateAttachmentsOnly(@Param("id") Long id, @Param("attachments") String attachments);

    int updateNoteAttachmentsOnly(@Param("id") Long id, @Param("attachments") String attachments);

    int deleteById(Long id);
}
