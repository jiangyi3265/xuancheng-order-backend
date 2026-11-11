package com.ruoyi.jiedan.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface JiedanProjectTesterMapper
{
    List<Long> list(Long orderId);
    String activeCustomer(Long userId);
    int remove(@Param("orderId") Long orderId, @Param("userId") Long userId);
    int add(@Param("orderId") Long orderId, @Param("userId") Long userId);
    int clearCustomer(@Param("account") String account, @Param("userId") Long userId);
}
