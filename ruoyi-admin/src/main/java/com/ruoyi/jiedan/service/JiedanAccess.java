package com.ruoyi.jiedan.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.jiedan.domain.JiedanOrder;
import com.ruoyi.jiedan.mapper.JiedanAccountMapper;

/**
 * 接单系统的数据权限判定：谁能看哪些项目、能管哪些客户。
 * 总管理员（admin 角色）看全部；下属管理员（employee 角色）只看负责人是自己的项目。
 * 身份一律取自登录令牌，不信任前端传来的任何“我是谁”。
 */
@Component
public class JiedanAccess
{
    public static final String ROLE_BOSS = "admin";
    public static final String ROLE_STAFF = "employee";
    public static final String DENIED = "项目不存在或无权访问";

    @Autowired
    private JiedanAccountMapper accountMapper;

    public Long currentUserId()
    {
        return SecurityUtils.getUserId();
    }

    public String currentName()
    {
        LoginUser u = SecurityUtils.getLoginUser();
        String nick = u.getUser() == null ? null : u.getUser().getNickName();
        return StringUtils.isNotEmpty(nick) ? nick : u.getUsername();
    }

    public boolean isBoss()
    {
        LoginUser u = SecurityUtils.getLoginUser();
        if (SecurityUtils.isAdmin(u.getUserId())) return true;
        if (u.getUser() != null && u.getUser().getRoles() != null)
        {
            for (SysRole r : u.getUser().getRoles())
            {
                if (ROLE_BOSS.equals(r.getRoleKey())) return true;
            }
        }
        return false;
    }

    public void requireBoss()
    {
        if (!isBoss()) throw new ServiceException("仅总管理员可操作");
    }

    /** 列表查询的负责人范围：总管理员不限（null），其他人只限自己。 */
    public Long ownerScope()
    {
        return isBoss() ? null : currentUserId();
    }

    public boolean canAccess(JiedanOrder o)
    {
        if (o == null) return false;
        if (isBoss()) return true;
        return o.getOwnerId() != null && o.getOwnerId().equals(currentUserId());
    }

    public List<Long> bossUserIds()
    {
        return accountMapper.selectBossUserIds();
    }

    /** 客户账号归谁负责；没有归属记录的归总管理员。 */
    public Long ownerOfAccount(Long userId)
    {
        Long owner = userId == null ? null : accountMapper.selectOwnerOf(userId);
        if (owner != null) return owner;
        List<Long> bosses = bossUserIds();
        return bosses.isEmpty() ? null : bosses.get(0);
    }

    public boolean canManageCustomer(Long customerUserId)
    {
        if (isBoss()) return true;
        Long owner = accountMapper.selectOwnerOf(customerUserId);
        return owner != null && owner.equals(currentUserId());
    }

    /** 下属管理员只能把项目绑到自己名下的客户账号。 */
    public void requireBindableCustomer(String account)
    {
        if (StringUtils.isEmpty(account) || isBoss()) return;
        Long uid = accountMapper.selectUserIdByName(account);
        if (uid == null) throw new ServiceException("客户账号不存在");
        if (!canManageCustomer(uid)) throw new ServiceException("无权绑定该客户账号");
    }

    /** 能否被指派为项目负责人：总管理员，或未停用的下属管理员。 */
    public boolean isAssignable(Long userId)
    {
        if (userId == null) return false;
        List<java.util.Map<String, Object>> rows = accountMapper.selectStaff(userId);
        return !rows.isEmpty() && "0".equals(String.valueOf(rows.get(0).get("status")));
    }
}
