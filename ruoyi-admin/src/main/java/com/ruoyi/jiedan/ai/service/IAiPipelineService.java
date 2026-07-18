package com.ruoyi.jiedan.ai.service;

import java.util.List;
import java.util.Map;

public interface IAiPipelineService
{
    List<Map<String, Object>> listProjects(String keyword, Integer enabled);
    Map<String, Object> getProject(Long id);
    Map<String, Object> createProject(Map<String, Object> body, String actor);
    Map<String, Object> updateProject(Map<String, Object> body, String actor);

    List<Map<String, Object>> listTasks(Long projectId, String status, String keyword);
    Map<String, Object> getTask(Long id);
    Map<String, Object> createTask(Map<String, Object> body, String actor);
    Map<String, Object> dispatch(Long id, String actor);
    Map<String, Object> retry(Long id, String actor);
    Map<String, Object> approve(Long id, Map<String, Object> body, String actor);
    Map<String, Object> reject(Long id, Map<String, Object> body, String actor);
    Map<String, Object> cancel(Long id, String actor);

    List<Map<String, Object>> candidates(Integer limit);
    Map<String, Object> claim(Long taskId, Map<String, Object> body);
    Map<String, Object> heartbeat(Long attemptId, Map<String, Object> body);
    Map<String, Object> appendLog(Long attemptId, Map<String, Object> body);
    Map<String, Object> finish(Long attemptId, Map<String, Object> body);
    List<Map<String, Object>> logs(Long attemptId, Long afterSeq, Integer limit);
}
