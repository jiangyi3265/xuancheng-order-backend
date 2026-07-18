package com.ruoyi.jiedan.ai.mapper;

import java.util.Date;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface AiEventMapper
{
    int insert(@Param("taskId") Long taskId, @Param("attemptId") Long attemptId,
        @Param("eventType") String eventType, @Param("actorType") String actorType,
        @Param("actorId") String actorId, @Param("eventData") String eventData,
        @Param("createTime") Date createTime);
    List<Map<String, Object>> selectByTaskId(Long taskId);
}
