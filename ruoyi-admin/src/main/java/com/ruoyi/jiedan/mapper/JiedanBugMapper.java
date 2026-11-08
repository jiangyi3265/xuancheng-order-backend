package com.ruoyi.jiedan.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.jiedan.domain.JiedanBug;

public interface JiedanBugMapper
{
    List<JiedanBug> selectByOrderId(Long orderId);

    JiedanBug selectById(Long id);

    List<JiedanBug> selectActionForReminder(@Param("orderId") Long orderId, @Param("bugId") Long bugId);

    JiedanBug selectCustomerBugForUpdate(@Param("id") Long id, @Param("account") String account);

    int insert(JiedanBug bug);

    int updateStatus(@Param("id") Long id, @Param("status") String status);

    int updateAttachments(@Param("id") Long id, @Param("attachments") String attachments);

    int deleteById(Long id);

    int deleteByOrderId(Long orderId);
}
