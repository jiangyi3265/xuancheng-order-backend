package com.ruoyi.jiedan.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;

/**
 * 后台账号（总管理员 / 下属管理员）与账号归属读写。
 */
public interface JiedanAccountMapper
{
    /** 所有有效的总管理员账号 id */
    List<Long> selectBossUserIds();

    /** 账号归属：下属 -> 上级，客户 -> 负责管理员；没有记录返回 null */
    Long selectOwnerOf(Long userId);

    int upsertOwner(@Param("userId") Long userId, @Param("ownerUserId") Long ownerUserId);

    int deleteOwner(Long userId);

    Long selectRoleIdByKey(String roleKey);

    Long selectUserIdByName(String userName);

    List<String> selectRoleKeys(Long userId);

    /** 后台账号列表（总管理员 + 下属管理员），userId 不为空时只查这一个 */
    List<Map<String, Object>> selectStaff(@Param("userId") Long userId);

    /** 归属某管理员的客户账号数 */
    int countOwnedCustomers(Long userId);

    /** 负责人为某账号的项目数（不含回收站） */
    int countOrdersByOwner(Long userId);

    /** 账号的显示名，找不到返回 null */
    String selectNickName(Long userId);
}
