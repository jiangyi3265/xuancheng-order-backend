-- 客户账号登录支持
-- 默认账号：customer
-- 默认密码：customer123

INSERT IGNORE INTO sys_role
VALUES (2, '客户', 'customer', 2, '2', 0, 0, '0', '0', 'admin', NOW(), '', NULL, '客户提交需求专用角色');

INSERT IGNORE INTO sys_user
VALUES (2, 101, 'customer', '客户账号', '2', 'customer@example.com', '', '2', '',
        '$2a$10$BNYaFwOidOKPaUdXALy8hugrW2cxuB5tYp2J8sE1K.d10fnzUHgsK',
        '0', '0', '', NULL, NOW(), 'admin', NOW(), '', NULL, '默认客户账号');

UPDATE sys_user
SET password = '$2a$10$BNYaFwOidOKPaUdXALy8hugrW2cxuB5tYp2J8sE1K.d10fnzUHgsK',
    status = '0',
    del_flag = '0'
WHERE user_name = 'customer';

INSERT IGNORE INTO sys_user_role VALUES (2, 2);
