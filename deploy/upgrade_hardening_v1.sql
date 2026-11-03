-- ============================================================
-- 上线前补丁包 V1（表结构部分）
--
-- 作用：
--   1) jiedan_order 增加「回收站」字段：删除项目不再物理删除，而是标记 del_flag=1，
--      总管理员/负责人可以在后台「回收站」恢复；
--   2) 新增 jiedan_audit_log 表：记录谁在什么时候做了什么重要操作（总管理员可查）。
--
-- 可重复执行（幂等），不删除、不改写任何已有业务数据。
-- 执行前请先 mysqldump 备份。已有数据库执行本脚本，不要重新导入 fresh-install.sql。
-- 建议顺序：先执行本脚本，再部署新版本后端（旧版后端不认识新字段，但不受影响）。
-- ============================================================

DROP PROCEDURE IF EXISTS `jiedan_hardening_v1`;

DELIMITER $$
CREATE PROCEDURE `jiedan_hardening_v1`()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'jiedan_order' AND COLUMN_NAME = 'del_flag') THEN
    ALTER TABLE `jiedan_order`
      ADD COLUMN `del_flag` tinyint NOT NULL DEFAULT '0' COMMENT '0正常 1已删除(回收站)',
      ADD COLUMN `delete_time` datetime DEFAULT NULL COMMENT '删除时间',
      ADD COLUMN `delete_by` varchar(64) DEFAULT NULL COMMENT '删除人',
      ADD COLUMN `delete_by_id` bigint DEFAULT NULL COMMENT '删除人账号id(客户删除为空)';
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'jiedan_order' AND INDEX_NAME = 'idx_jiedan_order_del_owner') THEN
    ALTER TABLE `jiedan_order` ADD KEY `idx_jiedan_order_del_owner` (`del_flag`, `owner_id`);
  END IF;
END$$
DELIMITER ;

CALL `jiedan_hardening_v1`();
DROP PROCEDURE `jiedan_hardening_v1`;

CREATE TABLE IF NOT EXISTS `jiedan_audit_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '日志ID',
  `create_time` datetime NOT NULL COMMENT '操作时间',
  `actor_id` bigint DEFAULT NULL COMMENT '操作人账号id(系统任务为空)',
  `actor_name` varchar(64) DEFAULT '' COMMENT '操作人姓名',
  `actor_role` varchar(16) DEFAULT '' COMMENT 'boss/employee/customer/system',
  `action` varchar(32) NOT NULL COMMENT '操作类型，如 order.delete',
  `target_type` varchar(16) DEFAULT '' COMMENT '对象类型 order/bug/staff/customer/account',
  `target_id` bigint DEFAULT NULL COMMENT '对象id',
  `target_name` varchar(200) DEFAULT '' COMMENT '对象名称(项目名/账号名)',
  `detail` varchar(1000) DEFAULT '' COMMENT '详情，如 金额 1000 -> 1500',
  `ip` varchar(64) DEFAULT '' COMMENT '操作人IP',
  PRIMARY KEY (`id`),
  KEY `idx_jiedan_audit_time` (`create_time`),
  KEY `idx_jiedan_audit_actor` (`actor_id`, `create_time`),
  KEY `idx_jiedan_audit_target` (`target_type`, `target_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='操作日志';
