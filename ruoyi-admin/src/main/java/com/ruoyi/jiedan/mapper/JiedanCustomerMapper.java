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
     * 列出客户账号（含归属管理员、名下项目数、未完成项目数）。
     * ownerId 不为空时只列归属该管理员的客户；没有归属记录的客户视为归 bossId。
     */
    List<Map<String, Object>> selectCustomerAccounts(@Param("ownerId") Long ownerId, @Param("bossId") Long bossId);

    /** 删除账号时解绑其名下项目 */
    int clearBinding(String account);
}
