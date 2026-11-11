CREATE TABLE IF NOT EXISTS jiedan_customer_scope (
    user_id bigint NOT NULL,
    scope_key varchar(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (user_id, scope_key)
) ENGINE=InnoDB;

-- Keep the draft identity even after permanent project deletion so it cannot be reused.
CREATE TABLE IF NOT EXISTS jiedan_project_draft_origin (
    order_id bigint NOT NULL PRIMARY KEY,
    draft_scope varchar(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE
) ENGINE=InnoDB;

-- Older accounts have no creation context. Preserve each recorded membership, including recoverable projects.
INSERT IGNORE INTO jiedan_customer_scope(user_id,scope_key)
SELECT u.user_id, IF(m.order_id IS NULL,'global',CONCAT('project:',m.order_id))
FROM sys_user u JOIN sys_user_role ur ON ur.user_id=u.user_id JOIN sys_role r ON r.role_id=ur.role_id
LEFT JOIN (
    SELECT old.user_id,o.id AS order_id FROM jiedan_order o
    JOIN sys_user old ON old.user_name COLLATE utf8mb4_general_ci=o.customer_account COLLATE utf8mb4_general_ci
    UNION SELECT user_id,order_id FROM jiedan_project_tester
) m ON m.user_id=u.user_id
WHERE u.del_flag='0' AND r.role_key='customer'
AND NOT EXISTS (SELECT 1 FROM jiedan_customer_scope known WHERE known.user_id=u.user_id);
