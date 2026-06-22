package com.ruoyi.jiedan.controller;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.jiedan.service.IJiedanOrderService;

/**
 * 推送设置 / 测试 / 手动提醒。
 */
@RestController
@RequestMapping("/jiedan/push")
@PreAuthorize("@ss.hasAnyRoles('admin,employee')")
public class JiedanPushController
{
    @Autowired
    private IJiedanOrderService service;

    @GetMapping("/config")
    public AjaxResult config()
    {
        return AjaxResult.success(service.getPushConfig());
    }

    @PostMapping("/config")
    public AjaxResult save(@RequestBody Map<String, Object> body)
    {
        service.savePushConfig(body);
        return AjaxResult.success();
    }

    @PostMapping("/test")
    public AjaxResult test(@RequestBody Map<String, Object> body)
    {
        Long memberId = body.get("memberId") == null ? null : Long.valueOf(body.get("memberId").toString());
        return AjaxResult.success(service.testPush(memberId));
    }

    @PostMapping("/reminder")
    public AjaxResult reminder()
    {
        return AjaxResult.success("已推送 " + service.runDeadlineReminder() + " 条提醒");
    }
}
