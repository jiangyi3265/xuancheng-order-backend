package com.ruoyi.jiedan.mapper;

import java.util.List;
import com.ruoyi.jiedan.domain.JiedanTimeline;

public interface JiedanTimelineMapper
{
    List<JiedanTimeline> selectByOrderId(Long orderId);

    int insert(JiedanTimeline timeline);

    int deleteByOrderId(Long orderId);
}
