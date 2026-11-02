-- ============================================================
-- 权限升级 V1（第 1 步：表结构 + 角色 + 客户归属回填）
--
-- 作用：
--   1) 新增「下属管理员」角色 employee（总管理员沿用原 admin 角色）；
--   2) 新增 jiedan_account_owner 表，记录“谁归谁管”：
--        - 下属管理员 -> 其上级（总管理员）
--        - 客户账号   -> 负责该客户的管理员（客户提交的需求自动落到此人名下）
--   3) 给现有客户账号回填归属：全部归总管理员。
--
-- 可重复执行（幂等），不会删除或改写任何业务数据。
-- 执行前请先 mysqldump 备份。已有数据库执行本脚本，不要重新导入 fresh-install.sql。
-- 执行完本脚本后，还需要在后台「成员管理」创建下属账号，
-- 再执行 upgrade_permission_v1_owner_remap.sql 把旧订单的负责人换成真实账号。
-- ============================================================

CREATE TABLE IF NOT EXISTS `jiedan_account_owner` (
  `user_id` bigint NOT NULL COMMENT '账号(sys_user.user_id)：下属管理员或客户',
  `owner_user_id` bigint NOT NULL COMMENT '归属/上级管理员(sys_user.user_id)',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  PRIMARY KEY (`user_id`),
  KEY `idx_jiedan_account_owner_owner` (`owner_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='账号归属（下属->上级、客户->负责管理员）';

INSERT INTO `sys_role`
    (`role_name`, `role_key`, `role_sort`, `data_scope`, `menu_check_strictly`, `dept_check_strictly`,
     `status`, `del_flag`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`)
SELECT '下属管理员', 'employee', 3, '1', 1, 1, '0', '0', 'admin', NOW(), '', NULL,
       '接单系统下属管理员，只能看到自己名下的项目'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `sys_role` WHERE `role_key` = 'employee' AND `del_flag` = '0');

-- 现有客户账号全部归总管理员（已有归属记录的不动）
INSERT INTO `jiedan_account_owner` (`user_id`, `owner_user_id`, `create_time`)
SELECT ur.user_id,
       (SELECT MIN(ur2.user_id) FROM `sys_user_role` ur2
          JOIN `sys_role` r2 ON r2.role_id = ur2.role_id
         WHERE r2.role_key = 'admin'),
       NOW()
FROM `sys_user_role` ur
JOIN `sys_role` r ON r.role_id = ur.role_id AND r.role_key = 'customer'
WHERE NOT EXISTS (SELECT 1 FROM `jiedan_account_owner` a WHERE a.user_id = ur.user_id)
  AND (SELECT MIN(ur3.user_id) FROM `sys_user_role` ur3
         JOIN `sys_role` r3 ON r3.role_id = ur3.role_id
        WHERE r3.role_key = 'admin') IS NOT NULL;
