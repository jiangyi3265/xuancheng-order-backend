package com.ruoyi.jiedan.controller;

import java.util.Collection;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
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
import com.ruoyi.framework.web.service.TokenService;
import com.ruoyi.jiedan.service.JiedanAuditService;
import com.ruoyi.system.service.ISysUserService;

/**
 * 当前登录人的自助操作：修改自己的密码。后台账号和客户账号都能用（只需已登录）。
 */
@RestController
@RequestMapping("/jiedan/account")
public class JiedanAccountController extends BaseController
{
    @Autowired
    private ISysUserService userService;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private JiedanAuditService audit;

    /** 修改自己的密码：需要旧密码；改完后该账号在其他设备上的登录会被踢下线，当前这台保持登录。 */
    @PutMapping("/password")
    public AjaxResult changePassword(@RequestBody Map<String, Object> body)
    {
        String oldPassword = text(body.get("oldPassword"));
        String newPassword = text(body.get("newPassword"));
        if (oldPassword.isEmpty()) return AjaxResult.error("请输入旧密码");
        if (newPassword.length() < 5 || newPassword.length() > 20) return AjaxResult.error("新密码长度需为 5-20 位");

        LoginUser loginUser = SecurityUtils.getLoginUser();
        // 缓存里的登录对象不带密码哈希，必须回数据库取当前真实的密码
        SysUser dbUser = userService.selectUserByUserName(loginUser.getUsername());
        if (dbUser == null) return AjaxResult.error("账号不存在");
        String current = dbUser.getPassword();
        if (!SecurityUtils.matchesPassword(oldPassword, current)) return AjaxResult.error("旧密码不正确");
        if (SecurityUtils.matchesPassword(newPassword, current)) return AjaxResult.error("新密码不能与旧密码相同");
        if (newPassword.equalsIgnoreCase(loginUser.getUsername())) return AjaxResult.error("新密码不能与登录账号相同");

        String encrypted = SecurityUtils.encryptPassword(newPassword);
        if (userService.resetUserPwd(loginUser.getUserId(), encrypted) <= 0) return AjaxResult.error("修改密码异常，请联系管理员");

        // 当前会话继续有效，缓存里的密码同步成新的；其余设备的会话作废
        loginUser.getUser().setPassword(encrypted);
        tokenService.setLoginUser(loginUser);
        kickOthers(loginUser);
        audit.record("account.password", "account", loginUser.getUserId(), loginUser.getUsername(), "修改了自己的登录密码");
        return AjaxResult.success("密码已修改");
    }

    private void kickOthers(LoginUser me)
    {
        Collection<String> keys = redisCache.keys(CacheConstants.LOGIN_TOKEN_KEY + "*");
        for (String key : keys)
        {
            LoginUser other = redisCache.getCacheObject(key);
            if (other != null && other.getUserId() != null && other.getUserId().equals(me.getUserId())
                    && other.getToken() != null && !other.getToken().equals(me.getToken()))
            {
                redisCache.deleteObject(key);
            }
        }
    }

    private String text(Object o)
    {
        return o == null ? "" : o.toString();
    }
}
