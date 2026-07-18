package com.ruoyi.jiedan.ai.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.jiedan.ai.domain.AiTask;

public interface AiTaskMapper
{
    List<Map<String, Object>> selectList(@Param("projectId") Long projectId,
        @Param("status") String status, @Param("keyword") String keyword);
    Map<String, Object> selectDetail(Long id);
    AiTask selectById(Long id);
    int insert(AiTask task);
    int queue(@Param("id") Long id, @Param("updatedBy") String updatedBy);
    int claim(@Param("id") Long id, @Param("version") Integer version, @Param("updatedBy") String updatedBy);
    int bindAttempt(@Param("id") Long id, @Param("attemptId") Long attemptId);
    int markRunning(@Param("id") Long id, @Param("attemptId") Long attemptId);
    int markAwaitingReview(@Param("id") Long id, @Param("attemptId") Long attemptId);
    int markFailed(@Param("id") Long id, @Param("attemptId") Long attemptId);
    int requeueExpired(@Param("id") Long id, @Param("attemptId") Long attemptId);
    int approve(@Param("id") Long id, @Param("attemptId") Long attemptId,
        @Param("headSha") String headSha, @Param("diffSha") String diffSha, @Param("updatedBy") String updatedBy);
    int reject(@Param("id") Long id, @Param("attemptId") Long attemptId, @Param("updatedBy") String updatedBy);
    int cancel(@Param("id") Long id, @Param("updatedBy") String updatedBy);
    List<Map<String, Object>> selectCandidates(@Param("limit") Integer limit);
}
