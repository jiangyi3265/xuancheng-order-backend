package com.ruoyi.jiedan.mapper;

import java.util.List;
import com.ruoyi.jiedan.domain.JiedanBugUpdate;

public interface JiedanBugUpdateMapper
{
    List<JiedanBugUpdate> selectByBugId(Long bugId);

    int insert(JiedanBugUpdate update);

    int deleteByBugId(Long bugId);

    int deleteByOrderId(Long orderId);
}
