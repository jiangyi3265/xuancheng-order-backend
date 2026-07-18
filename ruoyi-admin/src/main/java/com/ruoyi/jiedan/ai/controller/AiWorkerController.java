package com.ruoyi.jiedan.ai.controller;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.jiedan.ai.service.IAiPipelineService;

/**
 * 自有算力 Worker 协议。候选接口不返回 prompt；只有成功认领并取得围栏令牌后才返回完整任务。
 */
@RestController
@RequestMapping("/jiedan/ai/worker")
@PreAuthorize("@ss.hasAnyRoles('admin,ai_worker')")
public class AiWorkerController extends BaseController
{
    @Autowired private IAiPipelineService service;

    @GetMapping("/candidates")
    public AjaxResult candidates(@RequestParam(required = false) Integer limit)
    {
        return AjaxResult.success(service.candidates(limit));
    }

    @PostMapping("/tasks/{id}/claim")
    public AjaxResult claim(@PathVariable Long id, @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(service.claim(id, body));
    }

    @PostMapping("/attempts/{id}/heartbeat")
    public AjaxResult heartbeat(@PathVariable Long id, @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(service.heartbeat(id, body));
    }

    @PostMapping("/attempts/{id}/logs")
    public AjaxResult log(@PathVariable Long id, @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(service.appendLog(id, body));
    }

    @PostMapping("/attempts/{id}/finish")
    public AjaxResult finish(@PathVariable Long id, @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(service.finish(id, body));
    }
}
