package com.ruoyi.jiedan.controller;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.jiedan.service.IJiedanOrderService;

/**
 * 接单订单 Controller。
 * 管理接口只允许管理端角色访问，客户账号只能提交需求。
 */
@RestController
@RequestMapping("/jiedan/order")
public class JiedanOrderController extends BaseController
{
    @Autowired
    private IJiedanOrderService service;

    @GetMapping("/list")
    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    public AjaxResult list()
    {
        return AjaxResult.success(service.listVO());
    }

    @GetMapping("/{id}")
    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    public AjaxResult get(@PathVariable Long id)
    {
        return AjaxResult.success(service.getVO(id));
    }

    @PostMapping
    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    public AjaxResult add(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(service.create(body));
    }

    @PutMapping
    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    public AjaxResult edit(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(service.update(body));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    public AjaxResult remove(@PathVariable Long id)
    {
        service.delete(id);
        return AjaxResult.success();
    }

    @PostMapping("/progress")
    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    public AjaxResult progress(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(service.addProgress(body));
    }

    @PutMapping("/status")
    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    public AjaxResult status(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(service.changeStatus(body));
    }

    @PutMapping("/read")
    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    public AjaxResult read(@RequestBody Map<String, Object> body)
    {
        Long id = body.get("id") == null ? null : Long.valueOf(body.get("id").toString());
        Long memberId = body.get("memberId") == null ? null : Long.valueOf(body.get("memberId").toString());
        return AjaxResult.success(service.markRead(id, memberId));
    }

    @PostMapping("/revision")
    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    public AjaxResult revision(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(service.addRevision(body));
    }

    @PostMapping("/nudge")
    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    public AjaxResult nudge(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(service.nudge(body));
    }

    /** 客户需求提交：必须先登录客户账号。 */
    @PostMapping("/intake")
    public AjaxResult intake(@RequestBody Map<String, Object> body)
    {
        String username = SecurityUtils.getUsername();
        body.put("channel", "form");
        body.put("status", "pending");
        body.remove("byMemberId");
        if (StringUtils.isEmpty((String) body.get("customer")))
        {
            body.put("customer", username);
        }
        if (StringUtils.isEmpty((String) body.get("byUserName")))
        {
            body.put("byUserName", "客户：" + username);
        }
        if (body.get("ownerId") == null)
        {
            body.put("ownerId", 2);
        }
        return AjaxResult.success(service.create(body));
    }
}
