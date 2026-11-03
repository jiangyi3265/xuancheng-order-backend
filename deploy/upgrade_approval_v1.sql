-- ============================================================
-- 流转与审批 V1（表结构部分）
--
-- 作用：
--   1) jiedan_order 增加 assign_state（接收状态）：老板把项目派给员工后是 'pending'，
--      员工点“接收”或“拒绝”后恢复为空；
--   2) 新增 jiedan_approval 表：员工的敏感操作（改报价 / 取消 / 删除 / 收款）先提交申请，
--      老板批准后才生效，每条申请的处理结果都留在这张表里。
--
-- 可重复执行（幂等），不删除、不改写任何已有业务数据（已有项目的接收状态默认为空，即“无需接收”）。
-- 执行前请先 mysqldump 备份。已有数据库执行本脚本，不要重新导入 fresh-install.sql。
-- 建议顺序：先执行本脚本，再部署新版本后端（旧版后端不认识新字段，但不受影响）。
-- 前置：已执行过 upgrade_permission_v1.sql、upgrade_hardening_v1.sql、upgrade_solo_v1.sql。
-- ============================================================

DROP PROCEDURE IF EXISTS `jiedan_approval_v1`;

DELIMITER $$
CREATE PROCEDURE `jiedan_approval_v1`()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'jiedan_order' AND COLUMN_NAME = 'assign_state') THEN
    ALTER TABLE `jiedan_order`
      ADD COLUMN `assign_state` varchar(10) NOT NULL DEFAULT '' COMMENT '接收状态：空=无需接收/已接收 pending=等负责人接收' AFTER `received_amount`;
  END IF;
END$$
DELIMITER ;

CALL `jiedan_approval_v1`();
DROP PROCEDURE `jiedan_approval_v1`;

CREATE TABLE IF NOT EXISTS `jiedan_approval` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '申请ID',
  `order_id` bigint NOT NULL COMMENT '订单ID',
  `type` varchar(16) NOT NULL COMMENT '申请类型 amount改报价 cancel取消 delete删除 payment收款',
  `payload` varchar(1000) DEFAULT '' COMMENT '申请内容(JSON)，如 {"amount":"4500"}',
  `reason` varchar(500) DEFAULT '' COMMENT '申请原因',
  `status` varchar(12) NOT NULL DEFAULT 'pending' COMMENT 'pending待批 approved已批准 rejected已驳回 withdrawn已撤回',
  `requester_id` bigint NOT NULL COMMENT '申请人账号id',
  `requester_name` varchar(64) DEFAULT '' COMMENT '申请人姓名',
  `create_time` datetime NOT NULL COMMENT '申请时间',
  `handler_id` bigint DEFAULT NULL COMMENT '处理人账号id',
  `handler_name` varchar(64) DEFAULT '' COMMENT '处理人姓名',
  `handle_note` varchar(500) DEFAULT '' COMMENT '处理说明（驳回原因等）',
  `handle_time` datetime DEFAULT NULL COMMENT '处理时间',
  PRIMARY KEY (`id`),
  KEY `idx_jiedan_approval_status` (`status`, `id`),
  KEY `idx_jiedan_approval_order` (`order_id`, `status`),
  KEY `idx_jiedan_approval_requester` (`requester_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='员工申请（需老板批准）';
