package com.ruoyi.jiedan.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;

/**
 * 客户账号读取 / 项目解绑（供管理端账号管理使用）
 */
public interface JiedanCustomerMapper
{
    /**
     * 列出客户账号（含负责人、名下项目数、未完成项目数）。
     * ownerId 不为空时只列归属该管理员的客户；没有归属记录的客户视为归 bossId。
     */
    List<Map<String, Object>> selectCustomerAccounts(@Param("ownerId") Long ownerId, @Param("bossId") Long bossId);

    boolean usernameUsed(String username);
    int reserveShortName(String username);
    int lockManualName(String username);
    int beginBatch(@Param("creator") long creator, @Param("key") String key, @Param("hash") String hash);
    Map<String,Object> lockBatch(@Param("creator") long creator, @Param("key") String key);
    int finishBatch(@Param("creator") long creator, @Param("key") String key, @Param("result") String result);

    /** 删除账号时解绑其名下项目 */
    int clearBinding(String account);
}
