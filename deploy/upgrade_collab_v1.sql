-- ============================================================
-- 协作补丁 V1（表结构部分）
--
-- 作用：
--   1) jiedan_order 增加「下一步」：做什么 / 谁来做 / 哪天前（内部字段，客户看不到）；
--   2) 新增 jiedan_mention：内部记录里 @同事 的提醒与已读回执。
--
-- 可重复执行（幂等），不删除、不改写任何已有业务数据。
-- 执行前请先 mysqldump 备份。建议顺序：先执行本脚本，再部署新版本后端。
-- ============================================================

DROP PROCEDURE IF EXISTS `jiedan_collab_v1`;

DELIMITER $$
CREATE PROCEDURE `jiedan_collab_v1`()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'jiedan_order' AND COLUMN_NAME = 'next_step') THEN
    ALTER TABLE `jiedan_order`
      ADD COLUMN `next_step` varchar(200) DEFAULT NULL COMMENT '下一步：做什么（内部）',
      ADD COLUMN `next_owner_id` bigint DEFAULT NULL COMMENT '下一步：谁来做（成员id）',
      ADD COLUMN `next_due` varchar(20) DEFAULT NULL COMMENT '下一步：截止日期 yyyy-MM-dd';
  END IF;
END$$
DELIMITER ;

CALL `jiedan_collab_v1`();
DROP PROCEDURE `jiedan_collab_v1`;

CREATE TABLE IF NOT EXISTS `jiedan_mention` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'ID',
  `order_id` bigint NOT NULL COMMENT '项目ID',
  `timeline_id` bigint NOT NULL COMMENT '内部记录ID',
  `member_id` bigint NOT NULL COMMENT '被 @ 的成员',
  `from_id` bigint DEFAULT NULL COMMENT '谁 @ 的',
  `from_name` varchar(64) DEFAULT '' COMMENT '谁 @ 的（显示名）',
  `content` varchar(200) DEFAULT '' COMMENT '摘要',
  `create_time` datetime NOT NULL COMMENT '时间',
  `read_time` datetime DEFAULT NULL COMMENT '被 @ 的人打开项目的时间，空=未读',
  PRIMARY KEY (`id`),
  KEY `idx_jiedan_mention_member_read` (`member_id`, `read_time`),
  KEY `idx_jiedan_mention_order` (`order_id`),
  KEY `idx_jiedan_mention_timeline` (`timeline_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='内部记录 @ 提醒';
