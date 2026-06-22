package com.ruoyi.jiedan.controller;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.jiedan.service.IJiedanOrderService;

/**
 * 接单订单 Controller（玄成科技接单系统）
 * 管理接口需登录；仅 /intake（客户提交）匿名开放。
 */
@RestController
@RequestMapping("/jiedan/order")
public class JiedanOrderController extends BaseController
{
    @Autowired
    private IJiedanOrderService service;

    @GetMapping("/list")
    public AjaxResult list()
    {
        return AjaxResult.success(service.listVO());
    }

    @GetMapping("/{id}")
    public AjaxResult get(@PathVariable Long id)
    {
        return AjaxResult.success(service.getVO(id));
    }

    @PostMapping
    public AjaxResult add(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(service.create(body));
    }

    @PutMapping
    public AjaxResult edit(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(service.update(body));
    }

    @DeleteMapping("/{id}")
    public AjaxResult remove(@PathVariable Long id)
    {
        service.delete(id);
        return AjaxResult.success();
    }

    @PostMapping("/progress")
    public AjaxResult progress(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(service.addProgress(body));
    }

    @PutMapping("/status")
    public AjaxResult status(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(service.changeStatus(body));
    }

    @PutMapping("/read")
    public AjaxResult read(@RequestBody Map<String, Object> body)
    {
        Long id = body.get("id") == null ? null : Long.valueOf(body.get("id").toString());
        Long memberId = body.get("memberId") == null ? null : Long.valueOf(body.get("memberId").toString());
        return AjaxResult.success(service.markRead(id, memberId));
    }

    @PostMapping("/revision")
    public AjaxResult revision(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(service.addRevision(body));
    }

    @PostMapping("/nudge")
    public AjaxResult nudge(@RequestBody Map<String, Object> body)
    {
        return AjaxResult.success(service.nudge(body));
    }

    /** 客户需求提交（公开匿名，无需登录） */
    @Anonymous
    @PostMapping("/intake")
    public AjaxResult intake(@RequestBody Map<String, Object> body)
    {
        body.put("channel", "form");
        body.put("status", "pending");
        body.remove("byMemberId"); // 置空 => 两人都通知
        if (body.get("ownerId") == null) body.put("ownerId", 2);
        return AjaxResult.success(service.create(body));
    }
}
