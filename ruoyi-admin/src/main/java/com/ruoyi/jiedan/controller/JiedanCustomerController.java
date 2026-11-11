package com.ruoyi.jiedan.controller;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.redis.RedisCache;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.entity.SysRole;
import com.ruoyi.common.core.domain.entity.SysUser;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.jiedan.mapper.JiedanAccountMapper;
import com.ruoyi.jiedan.mapper.JiedanCustomerMapper;
import com.ruoyi.jiedan.mapper.JiedanProjectTesterMapper;
import com.ruoyi.jiedan.mapper.JiedanIssueReviewMapper;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.jiedan.service.JiedanAccess;
import com.ruoyi.jiedan.service.JiedanAuditService;
import com.ruoyi.system.service.ISysRoleService;
import com.ruoyi.system.service.ISysUserService;

/**
 * 客户账号管理 Controller（总管理员 / 下属管理员使用）。
 * 每个客户账号归属一位管理员：总管理员能管全部客户，下属管理员只能管归属自己的客户。
 * 客户提交的需求会自动落到其负责人名下。
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

    @Autowired
    private JiedanProjectTesterMapper testers;

    @Autowired
    private JiedanIssueReviewMapper issueReviews;

    @Autowired
    private JiedanAccountMapper accountMapper;

    @Autowired
    private JiedanAccess access;

    @Autowired
    private JiedanAuditService audit;

    @Autowired
    private RedisCache redisCache;

    /** 客户账号列表（含名下项目数 / 未完成数）；下属管理员只看到自己的客户 */
    @GetMapping("/list")
    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    public AjaxResult list(@RequestParam(defaultValue = "false") boolean picker,
                           @RequestParam(required = false) Long projectId, @RequestParam(required = false) String draftKey)
    {
        Long scope = access.isBoss() ? null : access.currentUserId();
        List<Long> bosses = access.bossUserIds();
        Long bossId = bosses.isEmpty() ? null : bosses.get(0);
        String selectedScope = picker || projectId != null || draftKey != null ? access.customerScope(projectId, draftKey) : null;
        return AjaxResult.success(customerMapper.selectCustomerAccounts(scope, bossId, selectedScope, projectId));
    }

    /** 新建客户账号；总管理员可指定负责人，下属管理员只能建在自己名下 */
    @PostMapping
    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AjaxResult add(@RequestBody Map<String, Object> body)
    {
        String userName = trim(body.get("userName"));
        String nickName = trim(body.get("nickName"));
        String password = body.get("password") == null ? "" : body.get("password").toString();
        if (StringUtils.isEmpty(userName) && !Boolean.TRUE.equals(body.get("autoUsername"))) return AjaxResult.error("请填写客户账号");
        if (password.length() < 5 || password.length() > 20) return AjaxResult.error("密码长度需为 5-20 位");

        Long ownerId = access.currentUserId();
        if (access.isBoss())
        {
            Long requested = asLong(body.get("ownerId"));
            if (requested != null)
            {
                if (!access.isAssignable(requested)) return AjaxResult.error("负责人无效或已停用");
                ownerId = requested;
            }
        }

        String scope = access.customerScope(body.get("projectId"), body.get("draftKey"));
        if (StringUtils.isEmpty(userName)) userName = allocateShortName();
        else
        {
            customerMapper.lockManualName(userName);
            SysUser user = new SysUser();
            user.setUserName(userName);
            if (!userService.checkUserNameUnique(user)) return AjaxResult.error("账号「" + userName + "」已存在");
        }
        return AjaxResult.success("客户账号创建成功", createAccount(userName, nickName, password, ownerId, customerRoleId(), scope));
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
    private static final int MAX_BATCH = 50;

    @PostMapping("/batch")
    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AjaxResult batch(@RequestBody Map<String, Object> body)
    {
        String key = trim(body.get("requestKey"));
        if (!key.matches("[A-Za-z0-9_-]{16,64}")) throw new ServiceException("批次标识无效，请重新打开");
        Long count = asLong(body.get("count"));
        if (count == null || count < 1 || count > MAX_BATCH) throw new ServiceException("每批可创建 1 至 50 个账号");
        Object rawNames = body.getOrDefault("names", List.of());
        if (!(rawNames instanceof List<?>)) throw new ServiceException("请每行输入一个姓名");
        List<?> supplied = (List<?>) rawNames;
        if (!supplied.isEmpty() && supplied.size() != count) throw new ServiceException("姓名数量与创建人数不一致");
        List<String> names = new ArrayList<>();
        for (Object name : supplied)
        {
            String nickname = trim(name);
            if (!(name instanceof String) || nickname.isEmpty() || nickname.codePointCount(0, nickname.length()) > 30)
                throw new ServiceException("每个姓名请填写 1 至 30 个字");
            names.add(nickname);
        }
        long creator = access.currentUserId();
        String scope = access.customerScope(body.get("projectId"), body.get("draftKey"));
        // Preserve retries of pre-upgrade, unscoped batches.
        String hash = DigestUtils.sha256Hex(JSON.toJSONString("global".equals(scope) ? List.of(count,names) : List.of(count,names,scope)));
        customerMapper.beginBatch(creator, key, hash);
        Map<String,Object> receipt = customerMapper.lockBatch(creator, key);
        if (!hash.equals(receipt.get("hash"))) throw new ServiceException("此批次已使用，请重新创建");
        if (receipt.get("result") != null)
            return AjaxResult.success(JSON.parseArray(receipt.get("result").toString()));

        Long role = customerRoleId();
        List<Map<String,Object>> accounts = new ArrayList<>();
        for (int i = 0; i < count; i++)
        {
            String username = allocateShortName();
            accounts.add(createAccount(username, names.isEmpty() ? username : names.get(i), "123456", creator, role, scope));
        }
        customerMapper.finishBatch(creator, key, JSON.toJSONString(accounts));
        return AjaxResult.success(accounts);
    }

    private String allocateShortName()
    {
        while (true)
        {
            StringBuilder name = new StringBuilder(8);
            for (int i = 0; i < 8; i++) name.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            String username = name.toString();
            if (customerMapper.reserveShortName(username) == 0) continue;
            if (!customerMapper.usernameUsed(username)) return username;
        }
    }

    private Map<String,Object> createAccount(String userName, String nickName, String password, Long ownerId, Long role, String scope)
    {
        SysUser user = new SysUser();
        user.setUserName(userName);
        user.setNickName(StringUtils.isEmpty(nickName) ? userName : nickName);
        user.setPassword(SecurityUtils.encryptPassword(password));
        user.setStatus("0");
        user.setCreateBy(SecurityUtils.getUsername());
        user.setRemark("客户账号（后台创建）");
        user.setRoleIds(new Long[] { role });
        userService.insertUser(user);
        accountMapper.upsertOwner(user.getUserId(), ownerId);
        customerMapper.addScope(user.getUserId(), scope);
        audit.record("customer.create", "customer", user.getUserId(), userName + "（" + user.getNickName() + "）", "创建客户账号；负责人：" + ownerName(ownerId));
        return Map.of("userId", user.getUserId(), "userName", userName, "nickName", user.getNickName());
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
        if (!access.canManageCustomer(userId)) return AjaxResult.error("无权操作该客户账号");
        int rows = userService.resetUserPwd(userId, SecurityUtils.encryptPassword(password));
        if (rows > 0) kick(user.getUserName());
        audit.record("customer.reset", "customer", userId, user.getUserName() + "（" + user.getNickName() + "）", "重置了客户登录密码");
        return toAjax(rows);
    }

    /** 把客户转交给另一位管理员负责（仅总管理员） */
    @PutMapping("/owner")
    @PreAuthorize("@ss.hasRole('admin')")
    public AjaxResult owner(@RequestBody Map<String, Object> body)
    {
        Long userId = asLong(body.get("userId"));
        Long ownerId = asLong(body.get("ownerId"));
        if (userId == null || ownerId == null) return AjaxResult.error("缺少参数");
        if (userService.selectUserById(userId) == null || !isCustomer(userId)) return AjaxResult.error("非客户账号，禁止操作");
        if (!access.isAssignable(ownerId)) return AjaxResult.error("负责人无效或已停用");
        SysUser target = userService.selectUserById(userId);
        Long before = accountMapper.selectOwnerOf(userId);
        accountMapper.upsertOwner(userId, ownerId);
        audit.record("customer.owner", "customer", userId, target.getUserName() + "（" + target.getNickName() + "）", "负责人 " + ownerName(before) + " → " + ownerName(ownerId));
        return AjaxResult.success("已转交");
    }

    /** 删除客户账号（同时解绑其名下项目） */
    @DeleteMapping("/{userId}")
    @PreAuthorize("@ss.hasAnyRoles('admin,employee')")
    @Transactional
    public AjaxResult remove(@PathVariable Long userId)
    {
        SysUser user = userService.selectUserById(userId);
        if (user == null) return AjaxResult.error("账号不存在");
        if (!isCustomer(userId)) return AjaxResult.error("非客户账号，禁止删除");
        if (!access.canManageCustomer(userId)) return AjaxResult.error("无权操作该客户账号");
        testers.clearCustomer(user.getUserName(), userId);
        customerMapper.clearBinding(user.getUserName());
        issueReviews.cancelForCustomer(userId);
        accountMapper.deleteOwner(userId);
        userService.deleteUserById(userId);
        kick(user.getUserName());
        audit.record("customer.delete", "customer", userId, user.getUserName() + "（" + user.getNickName() + "）", "删除客户账号并解绑其名下项目");
        return AjaxResult.success("已删除账号并解绑其项目");
    }

    /** Resetting or deleting a customer must invalidate every existing session. */
    private void kick(String userName)
    {
        Collection<String> keys = redisCache.keys(CacheConstants.LOGIN_TOKEN_KEY + "*");
        for (String key : keys)
        {
            LoginUser session = redisCache.getCacheObject(key);
            if (session != null && userName.equals(session.getUsername())) redisCache.deleteObject(key);
        }
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
        Long id = accountMapper.selectRoleIdByKey("customer");
        if (id == null) throw new ServiceException("缺少客户角色配置");
        return id;
    }

    private String ownerName(Long userId)
    {
        if (userId == null) return "无";
        String name = accountMapper.selectNickName(userId);
        return StringUtils.isNotEmpty(name) ? name : "用户#" + userId;
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
