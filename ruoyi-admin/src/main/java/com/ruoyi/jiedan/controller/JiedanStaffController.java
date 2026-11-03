package com.ruoyi.jiedan.controller;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
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

import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.jiedan.mapper.JiedanAccountMapper;
import com.ruoyi.jiedan.service.JiedanAccess;
import com.ruoyi.jiedan.service.JiedanAuditService;
import com.ruoyi.system.service.ISysUserService;

/**
 * 成员管理：总管理员创建/停用/重置下属管理员账号；所有后台账号可读取自己的身份。
 */
@RestController
@RequestMapping("/jiedan/staff")
public class JiedanStaffController extends BaseController
{
    @Autowired
    private ISysUserService userService;

    @Autowired
    private JiedanAccountMapper accountMapper;

    @Autowired
    private JiedanAccess access;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private JiedanAuditService audit;

    /** 当前登录人的身份（前端据此显示角色、隐藏无权限的菜单）。 */
    @GetMapping("/me")
    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    public AjaxResult me()
    {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", access.currentUserId());
        vo.put("userName", SecurityUtils.getUsername());
        vo.put("nickName", access.currentName());
        boolean boss = access.isBoss();
        vo.put("role", boss ? "boss" : "employee");
        vo.put("roleLabel", roleLabel(boss));
        return AjaxResult.success(vo);
    }

    /** 总管理员：全部后台账号；下属管理员：只有自己。 */
    @GetMapping("/list")
    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    public AjaxResult list()
    {
        Long scope = access.isBoss() ? null : access.currentUserId();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : accountMapper.selectStaff(scope))
        {
            boolean boss = isTrue(row.get("isBoss"));
            Map<String, Object> vo = new LinkedHashMap<>();
            vo.put("id", row.get("userId"));
            vo.put("userName", row.get("userName"));
            vo.put("nickName", row.get("nickName"));
            vo.put("role", boss ? "boss" : "employee");
            vo.put("roleLabel", roleLabel(boss));
            vo.put("status", row.get("status"));
            vo.put("orderCount", row.get("orderCount"));
            vo.put("unfinishedCount", row.get("unfinishedCount"));
            vo.put("createTime", row.get("createTime"));
            result.add(vo);
        }
        result.sort((a, b) ->
        {
            int ra = "boss".equals(a.get("role")) ? 0 : 1;
            int rb = "boss".equals(b.get("role")) ? 0 : 1;
            if (ra != rb) return ra - rb;
            return Long.compare(((Number) a.get("id")).longValue(), ((Number) b.get("id")).longValue());
        });
        return AjaxResult.success(result);
    }

    /** 新建下属管理员（仅总管理员） */
    @PostMapping
    @PreAuthorize("@ss.hasRole('admin')")
    public AjaxResult add(@RequestBody Map<String, Object> body)
    {
        String userName = trim(body.get("userName"));
        String nickName = trim(body.get("nickName"));
        String password = body.get("password") == null ? "" : body.get("password").toString();
        if (StringUtils.isEmpty(userName)) return AjaxResult.error("请填写登录账号");
        if (userName.length() > 30 || userName.matches(".*\\s.*")) return AjaxResult.error("登录账号不能超过 30 位，且不能含空格");
        if (password.length() < 5 || password.length() > 20) return AjaxResult.error("密码长度需为 5-20 位");

        SysUser user = new SysUser();
        user.setUserName(userName);
        if (!userService.checkUserNameUnique(user)) return AjaxResult.error("账号「" + userName + "」已存在");

        Long roleId = accountMapper.selectRoleIdByKey(JiedanAccess.ROLE_STAFF);
        if (roleId == null) return AjaxResult.error("缺少「下属管理员」角色，请先执行 upgrade_permission_v1.sql");

        user.setNickName(StringUtils.isEmpty(nickName) ? userName : nickName);
        user.setPassword(SecurityUtils.encryptPassword(password));
        user.setStatus("0");
        user.setCreateBy(SecurityUtils.getUsername());
        user.setRemark("下属管理员（后台创建）");
        user.setRoleIds(new Long[] { roleId });
        userService.insertUser(user);
        accountMapper.upsertOwner(user.getUserId(), access.currentUserId());
        audit.record("staff.create", "staff", user.getUserId(), userName + "（" + user.getNickName() + "）", "创建下属管理员账号");
        return AjaxResult.success("下属管理员创建成功");
    }

    /** 重置下属管理员密码，并让其已登录的会话失效 */
    @PutMapping("/reset")
    @PreAuthorize("@ss.hasRole('admin')")
    public AjaxResult reset(@RequestBody Map<String, Object> body)
    {
        Long userId = asLong(body.get("userId"));
        String password = body.get("password") == null ? "" : body.get("password").toString();
        if (userId == null) return AjaxResult.error("缺少 userId");
        if (password.length() < 5 || password.length() > 20) return AjaxResult.error("密码长度需为 5-20 位");
        SysUser user = requireStaffMember(userId);
        if (user == null) return AjaxResult.error("不是下属管理员账号，禁止操作");
        int rows = userService.resetUserPwd(userId, SecurityUtils.encryptPassword(password));
        kick(user.getUserName());
        audit.record("staff.reset", "staff", userId, user.getUserName() + "（" + user.getNickName() + "）", "重置了登录密码，并让其已登录设备下线");
        return toAjax(rows);
    }

    /** 启用 / 停用下属管理员；停用后立即踢下线 */
    @PutMapping("/status")
    @PreAuthorize("@ss.hasRole('admin')")
    public AjaxResult status(@RequestBody Map<String, Object> body)
    {
        Long userId = asLong(body.get("userId"));
        String status = trim(body.get("status"));
        if (userId == null) return AjaxResult.error("缺少 userId");
        if (!"0".equals(status) && !"1".equals(status)) return AjaxResult.error("状态不正确");
        SysUser user = requireStaffMember(userId);
        if (user == null) return AjaxResult.error("不是下属管理员账号，禁止操作");
        SysUser upd = new SysUser();
        upd.setUserId(userId);
        upd.setStatus(status);
        upd.setUpdateBy(SecurityUtils.getUsername());
        int rows = userService.updateUserStatus(upd);
        if ("1".equals(status)) kick(user.getUserName());
        audit.record("staff.status", "staff", userId, user.getUserName() + "（" + user.getNickName() + "）", "1".equals(status) ? "停用账号" : "启用账号");
        return toAjax(rows);
    }

    /** 删除下属管理员：名下没有项目、没有客户账号时才允许，否则请先停用或转交 */
    @DeleteMapping("/{userId}")
    @PreAuthorize("@ss.hasRole('admin')")
    public AjaxResult remove(@PathVariable Long userId)
    {
        SysUser user = requireStaffMember(userId);
        if (user == null) return AjaxResult.error("不是下属管理员账号，禁止删除");
        int orders = accountMapper.countOrdersByOwner(userId);
        if (orders > 0) return AjaxResult.error("名下还有 " + orders + " 个项目，请先转派后再删除（或直接停用账号）");
        int customers = accountMapper.countOwnedCustomers(userId);
        if (customers > 0) return AjaxResult.error("名下还有 " + customers + " 个客户账号，请先转交后再删除（或直接停用账号）");
        kick(user.getUserName());
        accountMapper.deleteOwner(userId);
        userService.deleteUserById(userId);
        audit.record("staff.delete", "staff", userId, user.getUserName() + "（" + user.getNickName() + "）", "删除下属管理员账号");
        return AjaxResult.success("已删除账号");
    }

    // ---------------- 内部辅助 ----------------
    /** 只允许操作“下属管理员”：必须是 employee 角色且不是 admin 角色。 */
    private SysUser requireStaffMember(Long userId)
    {
        SysUser user = userService.selectUserById(userId);
        if (user == null) return null;
        List<String> keys = accountMapper.selectRoleKeys(userId);
        if (keys.contains(JiedanAccess.ROLE_BOSS) || !keys.contains(JiedanAccess.ROLE_STAFF)) return null;
        return user;
    }

    /** 让某账号已登录的令牌全部失效。 */
    private void kick(String userName)
    {
        Collection<String> keys = redisCache.keys(CacheConstants.LOGIN_TOKEN_KEY + "*");
        for (String key : keys)
        {
            LoginUser lu = redisCache.getCacheObject(key);
            if (lu != null && userName.equals(lu.getUsername())) redisCache.deleteObject(key);
        }
    }

    private String roleLabel(boolean boss)
    {
        return boss ? "总管理员" : "下属管理员";
    }

    private boolean isTrue(Object o)
    {
        if (o == null) return false;
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof Number) return ((Number) o).intValue() != 0;
        return "1".equals(o.toString()) || "true".equalsIgnoreCase(o.toString());
    }

    private String trim(Object o)
    {
        return o == null ? "" : o.toString().trim();
    }

    private Long asLong(Object o)
    {
        if (o == null) return null;
        try { return Long.valueOf(o.toString().trim()); } catch (Exception e) { return null; }
    }
}
