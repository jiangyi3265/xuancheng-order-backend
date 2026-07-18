package com.ruoyi.jiedan.ai.controller;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.jiedan.ai.service.IAiPipelineService;

/** 人工控制面。该接口只批准代码指纹，不负责自动合并或生产发布。 */
@RestController
@RequestMapping("/jiedan/ai")
@PreAuthorize("@ss.hasAnyRoles('admin,employee')")
public class AiPipelineController extends BaseController
{
    @Autowired private IAiPipelineService service;

    @GetMapping("/projects")
    public AjaxResult projects(@RequestParam(required = false) String keyword,
        @RequestParam(required = false) Integer enabled)
    {
        return AjaxResult.success(service.listProjects(keyword, enabled));
    }

    @GetMapping("/projects/{id}")
    public AjaxResult project(@PathVariable Long id)
    {
        return AjaxResult.success(service.getProject(id));
    }

    @PostMapping("/projects")
    public AjaxResult createProject(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(service.createProject(body, SecurityUtils.getUsername()));
    }

    @PutMapping("/projects")
    public AjaxResult updateProject(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(service.updateProject(body, SecurityUtils.getUsername()));
    }

    @GetMapping("/tasks")
    public AjaxResult tasks(@RequestParam(required = false) Long projectId,
        @RequestParam(required = false) String status, @RequestParam(required = false) String keyword)
    {
        return AjaxResult.success(service.listTasks(projectId, status, keyword));
    }

    @GetMapping("/tasks/{id}")
    public AjaxResult task(@PathVariable Long id)
    {
        return AjaxResult.success(service.getTask(id));
    }

    @PostMapping("/tasks")
    public AjaxResult createTask(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(service.createTask(body, SecurityUtils.getUsername()));
    }

    @PostMapping("/tasks/{id}/dispatch")
    public AjaxResult dispatch(@PathVariable Long id)
    {
        return AjaxResult.success(service.dispatch(id, SecurityUtils.getUsername()));
    }

    @PostMapping("/tasks/{id}/retry")
    public AjaxResult retry(@PathVariable Long id)
    {
        return AjaxResult.success(service.retry(id, SecurityUtils.getUsername()));
    }

    @PostMapping("/tasks/{id}/approve")
    public AjaxResult approve(@PathVariable Long id, @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(service.approve(id, body, SecurityUtils.getUsername()));
    }

    @PostMapping("/tasks/{id}/reject")
    public AjaxResult reject(@PathVariable Long id, @RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(service.reject(id, body, SecurityUtils.getUsername()));
    }

    @PostMapping("/tasks/{id}/cancel")
    public AjaxResult cancel(@PathVariable Long id)
    {
        return AjaxResult.success(service.cancel(id, SecurityUtils.getUsername()));
    }

    @GetMapping("/attempts/{id}/logs")
    public AjaxResult logs(@PathVariable Long id, @RequestParam(required = false) Long afterSeq,
        @RequestParam(required = false) Integer limit)
    {
        return AjaxResult.success(service.logs(id, afterSeq, limit));
    }
}
