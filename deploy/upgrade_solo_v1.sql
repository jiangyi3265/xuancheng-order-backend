-- ============================================================
-- 记单收款版 V1（表结构部分）
--
-- 作用：
--   1) jiedan_order 增加 received_amount（已收金额）；
--   2) 新增 jiedan_payment 表：收款流水，每收一笔记一条（金额 / 日期 / 备注），
--      订单的已收金额 = 流水合计，数据站按收款日期统计每月实收；
--   3) 把升级前「paid=1（已收款）」的订单各补一条整笔收款流水，保持“已收款”不丢。
--
-- 可重复执行（幂等），不删除、不改写任何已有业务数据（只新增字段、新表和补录的流水）。
-- 执行前请先 mysqldump 备份。已有数据库执行本脚本，不要重新导入 fresh-install.sql。
-- 建议顺序：先执行本脚本，再部署新版本后端（旧版后端不认识新字段，但不受影响）。
-- 前置：已执行过 upgrade_permission_v1.sql 和 upgrade_hardening_v1.sql。
-- ============================================================

DROP PROCEDURE IF EXISTS `jiedan_solo_v1`;

DELIMITER $$
CREATE PROCEDURE `jiedan_solo_v1`()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'jiedan_order' AND COLUMN_NAME = 'received_amount') THEN
    ALTER TABLE `jiedan_order`
      ADD COLUMN `received_amount` decimal(12,2) NOT NULL DEFAULT '0.00' COMMENT '已收金额(收款流水合计)' AFTER `paid`;
  END IF;
END$$
DELIMITER ;

CALL `jiedan_solo_v1`();
DROP PROCEDURE `jiedan_solo_v1`;

CREATE TABLE IF NOT EXISTS `jiedan_payment` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '收款ID',
  `order_id` bigint NOT NULL COMMENT '订单ID',
  `amount` decimal(12,2) NOT NULL COMMENT '本次收款金额',
  `pay_date` varchar(10) NOT NULL COMMENT '收款日期 yyyy-MM-dd',
  `note` varchar(200) DEFAULT '' COMMENT '备注，如 定金/尾款/微信转账',
  `created_by` varchar(64) DEFAULT '' COMMENT '记录人',
  `create_time` datetime NOT NULL COMMENT '记录时间',
  PRIMARY KEY (`id`),
  KEY `idx_jiedan_payment_order` (`order_id`),
  KEY `idx_jiedan_payment_date` (`pay_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='收款流水';

-- 升级前标记过“已收款”的订单：补一条整笔收款流水（没有流水才补，重复执行不会重复补）
INSERT INTO `jiedan_payment` (`order_id`, `amount`, `pay_date`, `note`, `created_by`, `create_time`)
SELECT o.`id`, o.`amount`, DATE_FORMAT(IFNULL(o.`update_time`, NOW()), '%Y-%m-%d'), '升级前已标记收款', '系统', NOW()
  FROM `jiedan_order` o
 WHERE o.`paid` = 1 AND o.`amount` > 0
   AND NOT EXISTS (SELECT 1 FROM `jiedan_payment` p WHERE p.`order_id` = o.`id`);

UPDATE `jiedan_order` o
   SET o.`received_amount` = (SELECT IFNULL(SUM(p.`amount`), 0) FROM `jiedan_payment` p WHERE p.`order_id` = o.`id`)
 WHERE o.`paid` = 1 AND o.`received_amount` = 0;
