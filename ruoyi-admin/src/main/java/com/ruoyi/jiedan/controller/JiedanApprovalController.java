package com.ruoyi.jiedan.controller;

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

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.jiedan.service.IJiedanApprovalService;

/**
 * 员工申请：员工提交、撤回；老板批准、驳回。客户账号没有任何权限。
 */
@RestController
@RequestMapping("/jiedan/approval")
public class JiedanApprovalController
{
    @Autowired
    private IJiedanApprovalService service;

    @GetMapping("/list")
    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    public AjaxResult list(@RequestParam(required = false) String status)
    {
        return AjaxResult.success(service.list(status));
    }

    @PostMapping
    @PreAuthorize("@ss.hasRole('employee')")
    public AjaxResult create(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(service.create(body));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("@ss.hasRole('admin')")
    public AjaxResult approve(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body)
    {
        service.approve(id, body == null ? null : (String) body.get("note"));
        return AjaxResult.success();
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("@ss.hasRole('admin')")
    public AjaxResult reject(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body)
    {
        service.reject(id, body == null ? null : (String) body.get("note"));
        return AjaxResult.success();
    }

    @PostMapping("/{id}/withdraw")
    @PreAuthorize("@ss.hasRole('employee')")
    public AjaxResult withdraw(@PathVariable Long id)
    {
        service.withdraw(id);
        return AjaxResult.success();
    }
}
