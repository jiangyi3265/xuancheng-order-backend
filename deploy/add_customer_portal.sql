-- ============================================================
-- 客户门户升级：客户登录后管理自己的项目 + 双向沟通（图片/视频/语音）
-- 依赖：先执行 add_customer_login.sql（客户角色 customer / 默认账号 customer）
-- 所有演示客户密码均为 customer123
--
-- 【适用场景】仅用于「已导入旧版 xc_jiedan.sql 的存量数据库」升级。
-- 全新安装请直接导入最新 xc_jiedan.sql（已含 customer_account 列与演示客户账号），无需本脚本。
-- ============================================================

-- 1) 订单绑定归属客户账号（客户端凭此只看自己的项目）
ALTER TABLE jiedan_order
    ADD COLUMN customer_account VARCHAR(64) DEFAULT '' COMMENT '归属客户账号(sys_user.user_name)' AFTER customer;

-- 2) 新增演示客户账号（密码 customer123，与默认 customer 账号同一 hash）
INSERT IGNORE INTO sys_user
    (user_id, dept_id, user_name, nick_name, user_type, email, phonenumber, sex, avatar, password,
     status, del_flag, login_ip, login_date, create_by, create_time, update_by, update_time, remark)
VALUES
    (101, 100, 'laowang', '老王餐饮', '2', '', '', '2', '',
     '$2a$10$BNYaFwOidOKPaUdXALy8hugrW2cxuB5tYp2J8sE1K.d10fnzUHgsK',
     '0', '0', '', NULL, 'admin', NOW(), '', NULL, '演示客户账号'),
    (102, 100, 'hailan', '海蓝科技', '2', '', '', '2', '',
     '$2a$10$BNYaFwOidOKPaUdXALy8hugrW2cxuB5tYp2J8sE1K.d10fnzUHgsK',
     '0', '0', '', NULL, 'admin', NOW(), '', NULL, '演示客户账号');

-- 客户角色绑定
INSERT IGNORE INTO sys_user_role (user_id, role_id) VALUES (101, 2), (102, 2);

-- 3) 把演示订单归属到对应客户账号（方便登录后就能看到项目与聊天）
UPDATE jiedan_order SET customer_account = 'laowang' WHERE id = 1;
UPDATE jiedan_order SET customer_account = 'hailan'  WHERE id = 2;
