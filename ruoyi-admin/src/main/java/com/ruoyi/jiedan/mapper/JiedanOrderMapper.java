package com.ruoyi.jiedan.mapper;

import java.util.List;
import com.ruoyi.jiedan.domain.JiedanOrder;

public interface JiedanOrderMapper
{
    List<JiedanOrder> selectAll();

    List<JiedanOrder> selectByCustomerAccount(String customerAccount);

    List<java.util.Map<String, Object>> selectCustomerSummaries(String customerAccount);

    JiedanOrder selectById(Long id);

    JiedanOrder selectNotesById(Long id);

    int insert(JiedanOrder order);

    int update(JiedanOrder order);

    int deleteById(Long id);
}
