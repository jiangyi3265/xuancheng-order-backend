package com.ruoyi.jiedan.mapper;

import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;

/**
 * 操作日志读写。
 */
public interface JiedanAuditMapper
{
    int insert(@Param("time") Date time,
               @Param("actorId") Long actorId,
               @Param("actorName") String actorName,
               @Param("actorRole") String actorRole,
               @Param("action") String action,
               @Param("targetType") String targetType,
               @Param("targetId") Long targetId,
               @Param("targetName") String targetName,
               @Param("detail") String detail,
               @Param("ip") String ip);

    /** 筛选条件放在 q 里：action / targetType / actorId / keyword / beginTime / endTime */
    List<Map<String, Object>> selectPage(@Param("q") Map<String, Object> q,
                                         @Param("offset") int offset,
                                         @Param("limit") int limit);

    long count(@Param("q") Map<String, Object> q);
}
