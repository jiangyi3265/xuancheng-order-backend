-- 模拟线上“旧版”数据：1 个总管理员(admin)，旧成员编号 1/2 的订单，2 个客户账号
SET NAMES utf8mb4;

INSERT INTO sys_user (user_id, dept_id, user_name, nick_name, user_type, email, phonenumber, sex, avatar, password, status, del_flag, create_by, create_time, remark)
VALUES (1, 100, 'admin', '尚琦凯', '00', '', '', '0', '', '$2a$10$7JB720yubVSZvUI0rEqK/.VqGOZTH.ulu33dHOiBE8ByOhJIrdAu2', '0', '0', 'admin', NOW(), '总管理员'),
       (201, 100, 'cust_a', '甲客户', '2', '', '', '2', '', '$2a$10$BNYaFwOidOKPaUdXALy8hugrW2cxuB5tYp2J8sE1K.d10fnzUHgsK', '0', '0', 'admin', NOW(), '客户'),
       (202, 100, 'cust_b', '乙客户', '2', '', '', '2', '', '$2a$10$BNYaFwOidOKPaUdXALy8hugrW2cxuB5tYp2J8sE1K.d10fnzUHgsK', '0', '0', 'admin', NOW(), '客户');
INSERT INTO sys_user_role (user_id, role_id) VALUES (1, 1), (201, 2), (202, 2);

-- 旧订单：owner_id 1=总裁 2=副总裁（旧的写死成员编号）
INSERT INTO jiedan_order (id, order_no, title, channel, customer, customer_account, contact, amount, owner_id, status, priority, deadline, requirement, unread, paid, revisions, create_time, update_time)
VALUES (1, 'XC-2026-0001', '老板自己的单', 'wechat', '客户X', '', '', 1000, 1, 'doing', 'medium', '', '总裁亲自负责', '2', 0, 0, NOW(), NOW()),
       (2, 'XC-2026-0002', '副总裁的单', 'wechat', '客户Y', '', '', 2000, 2, 'doing', 'medium', '', '副总裁负责', '1', 0, 0, NOW(), NOW()),
       (3, 'XC-2026-0003', '甲客户的需求', 'form', '甲客户', 'cust_a', '', 3000, 2, 'pending', 'medium', '', '甲客户提交', '1,2', 0, 0, NOW(), NOW()),
       (4, 'XC-2026-0004', '乙客户的需求', 'form', '乙客户', 'cust_b', '', 4000, 1, 'pending', 'medium', '', '乙客户提交', '2', 0, 0, NOW(), NOW());
INSERT INTO jiedan_timeline (order_id, user_name, type, content, create_time)
VALUES (1, '尚琦凯', 'create', '创建订单', NOW()), (2, '尚琦凯', 'create', '创建订单', NOW()),
       (3, '甲客户', 'message', '甲客户的留言', NOW()), (4, '乙客户', 'message', '乙客户的留言', NOW());
INSERT INTO jiedan_bug (order_id, content, status, created_by, create_time) VALUES (1, '老板单的bug', 'open', '尚琦凯', NOW()), (2, '副总裁单的bug', 'open', '费鹏飞', NOW());

-- 推送：旧键 .1 / .2 ；测试时关闭推送，避免对外请求
UPDATE jiedan_setting SET setting_value = 'false' WHERE setting_key = 'push.enabled';
UPDATE jiedan_setting SET setting_value = 'KEY_BOSS' WHERE setting_key = 'push.sendkey.1';
UPDATE jiedan_setting SET setting_value = 'KEY_STAFF' WHERE setting_key = 'push.sendkey.2';

-- 测试环境关闭验证码
UPDATE sys_config SET config_value = 'false' WHERE config_key = 'sys.account.captchaEnabled';
