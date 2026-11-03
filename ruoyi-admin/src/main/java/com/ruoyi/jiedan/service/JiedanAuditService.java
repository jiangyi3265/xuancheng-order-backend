package com.ruoyi.jiedan.service;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.ip.IpUtils;
import com.ruoyi.jiedan.mapper.JiedanAuditMapper;

/**
 * 操作日志：记录谁在什么时候做了什么重要操作（删除、改金额、转派、账号管理、导出等）。
 * 写日志失败只记服务端日志，绝不影响业务操作本身。
 */
@Service
public class JiedanAuditService
{
    private static final Logger log = LoggerFactory.getLogger(JiedanAuditService.class);
    private static final int DETAIL_MAX = 1000;
    private static final int NAME_MAX = 200;

    @Autowired
    private JiedanAuditMapper mapper;

    @Autowired
    private JiedanAccess access;

    /** 以当前登录人的身份记一条；没有登录上下文（定时任务）时记为“系统”。 */
    public void record(String action, String targetType, Long targetId, String targetName, String detail)
    {
        try
        {
            Long actorId = null;
            String actorName = "系统";
            String actorRole = "system";
            try
            {
                LoginUser lu = SecurityUtils.getLoginUser();
                actorId = lu.getUserId();
                actorName = access.currentName();
                actorRole = roleCode(lu);
            }
            catch (Exception noLogin)
            {
                // 没有登录用户：保持“系统”
            }
            mapper.insert(new Date(), actorId, cut(actorName, 64), actorRole, action,
                    targetType == null ? "" : targetType, targetId,
                    cut(targetName, NAME_MAX), cut(detail, DETAIL_MAX), clientIp());
        }
        catch (Exception e)
        {
            log.warn("写操作日志失败 action={} target={}", action, targetId, e);
        }
    }

    /** 总管理员查日志：分页 + 筛选。 */
    public Map<String, Object> page(Map<String, Object> q, int pageNum, int pageSize)
    {
        int size = Math.max(1, Math.min(pageSize, 100));
        int offset = (Math.max(pageNum, 1) - 1) * size;
        List<Map<String, Object>> rows = mapper.selectPage(q, offset, size);
        for (Map<String, Object> row : rows)
        {
            Object t = row.get("createTime");
            if (t instanceof Date) row.put("createTime", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format((Date) t));
            else if (t instanceof java.time.LocalDateTime)
            {
                row.put("createTime", ((java.time.LocalDateTime) t).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        result.put("total", mapper.count(q));
        return result;
    }

    private String roleCode(LoginUser lu)
    {
        if (access.isBoss()) return "boss";
        if (lu.getUser() != null && lu.getUser().getRoles() != null)
        {
            for (SysRole r : lu.getUser().getRoles())
            {
                if (JiedanAccess.ROLE_STAFF.equals(r.getRoleKey())) return "employee";
                if ("customer".equals(r.getRoleKey())) return "customer";
            }
        }
        return "other";
    }

    private String clientIp()
    {
        try
        {
            String ip = IpUtils.getIpAddr();
            return ip == null ? "" : cut(ip, 64);
        }
        catch (Exception e)
        {
            return "";
        }
    }

    private String cut(String s, int max)
    {
        if (StringUtils.isEmpty(s)) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
