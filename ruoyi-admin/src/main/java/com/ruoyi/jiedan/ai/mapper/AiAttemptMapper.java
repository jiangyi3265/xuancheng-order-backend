package com.ruoyi.jiedan.ai.mapper;

import java.util.Date;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.jiedan.ai.domain.AiAttempt;

public interface AiAttemptMapper
{
    AiAttempt selectById(Long id);
    AiAttempt selectByRequestId(String requestId);
    int selectNextAttemptNo(Long taskId);
    int countActiveByProject(Long projectId);
    int insert(AiAttempt attempt);
    List<Map<String, Object>> selectByTaskId(Long taskId);
    List<AiAttempt> selectExpired(Date now);
    int heartbeat(@Param("id") Long id, @Param("fenceToken") String fenceToken,
        @Param("workerId") String workerId, @Param("leaseExpireTime") Date leaseExpireTime);
    int completeSuccess(AiAttempt attempt);
    int completeFailure(AiAttempt attempt);
    int expire(@Param("id") Long id, @Param("fenceToken") String fenceToken, @Param("now") Date now);
    int insertLog(@Param("attemptId") Long attemptId, @Param("seq") Long seq,
        @Param("level") String level, @Param("message") String message, @Param("createTime") Date createTime);
    List<Map<String, Object>> selectLogs(@Param("attemptId") Long attemptId, @Param("afterSeq") Long afterSeq,
        @Param("limit") Integer limit);
}
