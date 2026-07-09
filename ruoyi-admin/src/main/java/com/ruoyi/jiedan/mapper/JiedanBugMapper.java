package com.ruoyi.jiedan.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.jiedan.domain.JiedanBug;

public interface JiedanBugMapper
{
    List<JiedanBug> selectByOrderId(Long orderId);

    JiedanBug selectById(Long id);

    int insert(JiedanBug bug);

    int updateStatus(@Param("id") Long id, @Param("status") String status);

    int deleteById(Long id);

    int deleteByOrderId(Long orderId);
}
