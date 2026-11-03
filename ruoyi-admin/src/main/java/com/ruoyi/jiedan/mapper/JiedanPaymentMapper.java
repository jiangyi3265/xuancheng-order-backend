package com.ruoyi.jiedan.mapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.jiedan.domain.JiedanPayment;

public interface JiedanPaymentMapper
{
    List<JiedanPayment> selectByOrderId(Long orderId);

    JiedanPayment selectById(Long id);

    int insert(JiedanPayment payment);

    int deleteById(Long id);

    int deleteByOrderId(Long orderId);

    /** 某订单的收款合计（没有流水返回 0） */
    BigDecimal sumByOrderId(Long orderId);

    /** 从 fromDate 起按月汇总的实收（只算未删除的项目）；ownerId 不为空时只算负责人是该账号的项目 */
    List<Map<String, Object>> selectMonthlyIncome(@Param("ownerId") Long ownerId, @Param("fromDate") String fromDate);

    /** 累计实收（只算未删除的项目） */
    BigDecimal selectTotalIncome(@Param("ownerId") Long ownerId);
}
