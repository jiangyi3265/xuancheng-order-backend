package com.ruoyi.jiedan.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.jiedan.service.JiedanAuditService;

/**
 * 操作日志查询：仅总管理员。
 */
@RestController
@RequestMapping("/jiedan/audit")
public class JiedanAuditController
{
    @Autowired
    private JiedanAuditService audit;

    @GetMapping("/list")
    @PreAuthorize("@ss.hasRole('admin')")
    public AjaxResult list(@RequestParam(defaultValue = "1") int pageNum,
                           @RequestParam(defaultValue = "20") int pageSize,
                           @RequestParam(required = false) String action,
                           @RequestParam(required = false) String targetType,
                           @RequestParam(required = false) Long actorId,
                           @RequestParam(required = false) String keyword,
                           @RequestParam(required = false) String beginTime,
                           @RequestParam(required = false) String endTime)
    {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("action", action);
        q.put("targetType", targetType);
        q.put("actorId", actorId);
        q.put("keyword", keyword == null ? null : keyword.trim());
        q.put("beginTime", dateOnly(beginTime));
        q.put("endTime", dateOnly(endTime));
        return AjaxResult.success(audit.page(q, pageNum, pageSize));
    }

    /** 只接受 yyyy-MM-dd，其余一律忽略（避免把乱七八糟的字符串带进 SQL 比较）。 */
    private String dateOnly(String s)
    {
        return s != null && s.matches("\\d{4}-\\d{2}-\\d{2}") ? s : null;
    }
}
