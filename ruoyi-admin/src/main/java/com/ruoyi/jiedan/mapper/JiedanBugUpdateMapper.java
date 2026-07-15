package com.ruoyi.jiedan.mapper;

import java.util.List;
import com.ruoyi.jiedan.domain.JiedanBugUpdate;

public interface JiedanBugUpdateMapper
{
    List<JiedanBugUpdate> selectByBugId(Long bugId);

    List<JiedanBugUpdate> selectByOrderId(Long orderId);

    int insert(JiedanBugUpdate update);

    int updateAttachments(@org.apache.ibatis.annotations.Param("id") Long id,
                          @org.apache.ibatis.annotations.Param("attachments") String attachments);

    int deleteByBugId(Long bugId);

    int deleteByOrderId(Long orderId);
}
