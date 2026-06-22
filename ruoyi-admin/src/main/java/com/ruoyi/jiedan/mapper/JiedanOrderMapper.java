package com.ruoyi.jiedan.mapper;

import java.util.List;
import com.ruoyi.jiedan.domain.JiedanOrder;

public interface JiedanOrderMapper
{
    List<JiedanOrder> selectAll();

    JiedanOrder selectById(Long id);

    int insert(JiedanOrder order);

    int update(JiedanOrder order);

    int deleteById(Long id);
}
