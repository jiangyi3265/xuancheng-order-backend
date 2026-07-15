package com.ruoyi.jiedan.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.jiedan.domain.JiedanOrder;

public interface JiedanOrderMapper
{
    List<JiedanOrder> selectAll();

    List<Map<String, Object>> selectAdminSummaries();

    List<JiedanOrder> selectByCustomerAccount(String customerAccount);

    List<java.util.Map<String, Object>> selectCustomerSummaries(String customerAccount);

    Long selectVersionById(Long id);

    Long selectVersionForCustomer(@Param("id") Long id, @Param("customerAccount") String customerAccount);

    JiedanOrder selectById(Long id);

    JiedanOrder selectNotesById(Long id);

    int insert(JiedanOrder order);

    int update(JiedanOrder order);

    int updateAttachmentsOnly(@Param("id") Long id, @Param("attachments") String attachments);

    int updateNoteAttachmentsOnly(@Param("id") Long id, @Param("attachments") String attachments);

    int deleteById(Long id);
}
