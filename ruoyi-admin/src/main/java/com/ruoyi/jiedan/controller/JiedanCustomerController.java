package com.ruoyi.jiedan.controller;

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

import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.jiedan.mapper.JiedanCustomerMapper;
import com.ruoyi.system.service.ISysRoleService;
import com.ruoyi.system.service.ISysUserService;

/**
 * 客户账号管理 Controller（老板 / 员工使用）。
 * 老板和员工可为客户创建登录账号并绑定项目；项目完成后可删除账号（同时解绑名下项目）。
 */
@RestController
@RequestMapping("/jiedan/customer")
public class JiedanCustomerController extends BaseController
{
    @Autowired
    private ISysUserService userService;

    @Autowired
    private ISysRoleService roleService;

    @Autowired
    private JiedanCustomerMapper customerMapper;

    /** 客户账号列表（含名下项目数 / 未完成数） */
    @GetMapping("/list")
    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    public AjaxResult list()
    {
        return AjaxResult.success(customerMapper.selectCustomerAccounts());
    }

    /** 新建客户账号 */
    @PostMapping
    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    public AjaxResult add(@RequestBody Map<String, Object> body)
    {
        String userName = trim(body.get("userName"));
        String nickName = trim(body.get("nickName"));
        String password = body.get("password") == null ? "" : body.get("password").toString();
        if (StringUtils.isEmpty(userName)) return AjaxResult.error("请填写客户账号");
        if (password.length() < 5 || password.length() > 20) return AjaxResult.error("密码长度需为 5-20 位");

        SysUser user = new SysUser();
        user.setUserName(userName);
        if (!userService.checkUserNameUnique(user)) return AjaxResult.error("账号「" + userName + "」已存在");

        user.setNickName(StringUtils.isEmpty(nickName) ? userName : nickName);
        user.setPassword(SecurityUtils.encryptPassword(password));
        user.setStatus("0");
        user.setCreateBy(SecurityUtils.getUsername());
        user.setRemark("客户账号（后台创建）");
        user.setRoleIds(new Long[] { customerRoleId() });
        userService.insertUser(user);
        return AjaxResult.success("客户账号创建成功");
    }

    /** 重置客户账号密码 */
    @PutMapping("/reset")
    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    public AjaxResult reset(@RequestBody Map<String, Object> body)
    {
        Long userId = asLong(body.get("userId"));
        String password = body.get("password") == null ? "" : body.get("password").toString();
        if (userId == null) return AjaxResult.error("缺少 userId");
        if (password.length() < 5 || password.length() > 20) return AjaxResult.error("密码长度需为 5-20 位");
        SysUser user = userService.selectUserById(userId);
        if (user == null || !isCustomer(userId)) return AjaxResult.error("非客户账号，禁止操作");
        return toAjax(userService.resetUserPwd(userId, SecurityUtils.encryptPassword(password)));
    }

    /** 删除客户账号（同时解绑其名下项目） */
    @DeleteMapping("/{userId}")
    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    public AjaxResult remove(@PathVariable Long userId)
    {
        SysUser user = userService.selectUserById(userId);
        if (user == null) return AjaxResult.error("账号不存在");
        if (!isCustomer(userId)) return AjaxResult.error("非客户账号，禁止删除");
        customerMapper.clearBinding(user.getUserName());
        userService.deleteUserById(userId);
        return AjaxResult.success("已删除账号并解绑其项目");
    }

    // ---------------- 内部辅助 ----------------
    private boolean isCustomer(Long userId)
    {
        for (SysRole r : roleService.selectRolesByUserId(userId))
        {
            if ("customer".equals(r.getRoleKey())) return true;
        }
        return false;
    }

    private Long customerRoleId()
    {
        for (SysRole r : roleService.selectRoleAll())
        {
            if ("customer".equals(r.getRoleKey())) return r.getRoleId();
        }
        return 2L; // 兜底：初始化脚本中的客户角色 id
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
