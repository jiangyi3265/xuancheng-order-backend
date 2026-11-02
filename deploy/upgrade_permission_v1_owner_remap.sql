-- ============================================================
-- 权限升级 V1（第 2 步：把旧订单的负责人换成真实账号）
--
-- 背景：旧版订单的 owner_id 是写死的“成员编号”（1=总裁，2=副总裁），
-- 新版 owner_id 存的是真实登录账号的 user_id。
--
-- 使用顺序：
--   1) 先执行 upgrade_permission_v1.sql；
--   2) 部署新版本后，用总管理员登录后台，在「成员管理」创建下属账号（如 费鹏飞）；
--   3) 把本文件最后一行 CALL 里的账号名改成那个下属的登录账号，执行一次。
--
-- 本脚本只能执行一次（执行后会写入标记，再次执行会报错中止）。
-- 它会：
--   - 旧 owner_id=1 的订单 -> 总管理员；旧 owner_id=2 的订单 -> 指定的下属账号；
--   - 清空订单“未读”标记（旧标记是旧成员编号，无法对应新账号）；
--   - 把推送 SendKey（push.sendkey.1 / .2）迁移到新账号对应的键上；
--   - 登记该下属的上级为总管理员。
-- 执行前请先 mysqldump 备份。
-- ============================================================

DROP PROCEDURE IF EXISTS `jiedan_remap_owner_v1`;

DELIMITER $$
CREATE PROCEDURE `jiedan_remap_owner_v1`(IN staff_login VARCHAR(30))
BEGIN
  DECLARE boss BIGINT;
  DECLARE staff BIGINT;
  DECLARE flag VARCHAR(10);
  DECLARE key1 VARCHAR(500);
  DECLARE key2 VARCHAR(500);

  SELECT setting_value INTO flag FROM jiedan_setting WHERE setting_key = 'perm.v1.remapped';
  IF flag = '1' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '已经执行过，禁止重复执行';
  END IF;

  SELECT MIN(ur.user_id) INTO boss
    FROM sys_user_role ur JOIN sys_role r ON r.role_id = ur.role_id
   WHERE r.role_key = 'admin';
  IF boss IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '找不到总管理员账号';
  END IF;

  SELECT u.user_id INTO staff
    FROM sys_user u
    JOIN sys_user_role ur ON ur.user_id = u.user_id
    JOIN sys_role r ON r.role_id = ur.role_id AND r.role_key = 'employee'
   WHERE u.user_name = staff_login AND u.del_flag = '0'
   LIMIT 1;
  IF staff IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '找不到该下属管理员账号，请先在后台「成员管理」创建';
  END IF;

  UPDATE jiedan_order
     SET owner_id = CASE owner_id WHEN 1 THEN boss WHEN 2 THEN staff ELSE owner_id END
   WHERE owner_id IN (1, 2);

  UPDATE jiedan_order SET unread = '';

  SELECT setting_value INTO key1 FROM jiedan_setting WHERE setting_key = 'push.sendkey.1';
  SELECT setting_value INTO key2 FROM jiedan_setting WHERE setting_key = 'push.sendkey.2';
  DELETE FROM jiedan_setting WHERE setting_key IN ('push.sendkey.1', 'push.sendkey.2');
  INSERT INTO jiedan_setting (setting_key, setting_value)
       VALUES (CONCAT('push.sendkey.', boss), IFNULL(key1, ''))
       ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value);
  INSERT INTO jiedan_setting (setting_key, setting_value)
       VALUES (CONCAT('push.sendkey.', staff), IFNULL(key2, ''))
       ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value);

  INSERT INTO jiedan_account_owner (user_id, owner_user_id, create_time)
       VALUES (staff, boss, NOW())
       ON DUPLICATE KEY UPDATE owner_user_id = VALUES(owner_user_id);

  INSERT INTO jiedan_setting (setting_key, setting_value) VALUES ('perm.v1.remapped', '1')
       ON DUPLICATE KEY UPDATE setting_value = '1';
END$$
DELIMITER ;

-- ↓↓↓ 把引号里的内容改成下属的登录账号，再执行 ↓↓↓
CALL `jiedan_remap_owner_v1`('请改成下属的登录账号');

DROP PROCEDURE IF EXISTS `jiedan_remap_owner_v1`;
