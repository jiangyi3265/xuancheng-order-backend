package com.ruoyi.jiedan.mapper;

import java.util.List;
import java.util.Map;

/**
 * 客户账号读取 / 项目解绑（供管理端账号管理使用）
 */
public interface JiedanCustomerMapper
{
    /** 列出所有客户账号（含名下项目数、未完成项目数） */
    List<Map<String, Object>> selectCustomerAccounts();

    /** 删除账号时解绑其名下项目 */
    int clearBinding(String account);
}
