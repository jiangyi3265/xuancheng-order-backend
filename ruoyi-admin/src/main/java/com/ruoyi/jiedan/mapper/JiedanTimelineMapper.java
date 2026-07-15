package com.ruoyi.jiedan.mapper;

import java.util.List;
import com.ruoyi.jiedan.domain.JiedanTimeline;

public interface JiedanTimelineMapper
{
    List<JiedanTimeline> selectByOrderId(Long orderId);

    int insert(JiedanTimeline timeline);

    int updateAttachments(@org.apache.ibatis.annotations.Param("id") Long id,
                          @org.apache.ibatis.annotations.Param("attachments") String attachments);

    int deleteByOrderId(Long orderId);
}
