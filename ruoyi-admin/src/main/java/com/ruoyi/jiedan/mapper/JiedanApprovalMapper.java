package com.ruoyi.jiedan.mapper;

import java.util.Date;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.jiedan.domain.JiedanApproval;

public interface JiedanApprovalMapper
{
    int insert(JiedanApproval approval);

    JiedanApproval selectById(Long id);

    /** 带项目名的列表：status / requesterId 为空表示不限；待批的排在最前面 */
    List<Map<String, Object>> selectList(@Param("status") String status, @Param("requesterId") Long requesterId,
                                         @Param("limit") int limit);

    /** 某项目上某类型还没处理的申请数量（防重复提交） */
    int countPending(@Param("orderId") Long orderId, @Param("type") String type);

    /** 只处理还在“待批”的申请；返回 0 表示已经被别人处理过 */
    int markHandled(@Param("id") Long id, @Param("status") String status, @Param("handlerId") Long handlerId,
                    @Param("handlerName") String handlerName, @Param("note") String note, @Param("time") Date time);

    int deleteByOrderId(Long orderId);
}
