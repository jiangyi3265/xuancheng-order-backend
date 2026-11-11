CREATE TABLE IF NOT EXISTS jiedan_task_turn (
  order_id bigint NOT NULL,
  task_key varchar(48) CHARACTER SET ascii NOT NULL,
  requester_id bigint NOT NULL,
  submitted_by bigint DEFAULT NULL,
  submitted_at datetime DEFAULT NULL,
  PRIMARY KEY(order_id,task_key),
  FOREIGN KEY (order_id) REFERENCES jiedan_order(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

START TRANSACTION;
SET @backfill_task_turn = NOT EXISTS (SELECT 1 FROM jiedan_setting WHERE setting_key='migration.taskTurn.v1');

-- Recover the recorded sender of the current next step.
INSERT IGNORE INTO jiedan_task_turn(order_id,task_key,requester_id)
SELECT o.id,'next',a.actor_id FROM jiedan_order o JOIN jiedan_audit_log a
ON a.id=(SELECT MAX(h.id) FROM jiedan_audit_log h WHERE h.target_type='order' AND h.target_id=o.id AND h.action='order.next')
WHERE @backfill_task_turn AND o.del_flag=0 AND o.status NOT IN ('done','cancelled') AND o.next_step<>'' AND a.actor_id<>COALESCE(o.next_owner_id,o.owner_id)
AND (a.actor_id=o.owner_id OR a.actor_role='boss');

-- Legacy manual marks did not record their author. Route them to the sole active administrator when unambiguous.
INSERT IGNORE INTO jiedan_task_turn(order_id,task_key,requester_id)
SELECT o.id,'manual',a.uid FROM jiedan_order o JOIN (
 SELECT MIN(u.user_id) uid FROM sys_user u JOIN sys_user_role ur ON ur.user_id=u.user_id
 JOIN sys_role r ON r.role_id=ur.role_id WHERE r.role_key='admin' AND u.status='0' AND u.del_flag='0'
 HAVING COUNT(DISTINCT u.user_id)=1
) a ON a.uid<>o.owner_id WHERE @backfill_task_turn AND o.del_flag=0 AND o.status NOT IN ('done','cancelled') AND o.handled=0 AND o.handled_time IS NOT NULL;

INSERT IGNORE INTO jiedan_task_turn(order_id,task_key,requester_id)
SELECT b.order_id,CONCAT('bug-',b.id),a.uid FROM jiedan_bug b JOIN jiedan_order o ON o.id=b.order_id
JOIN (SELECT MIN(u.user_id) uid FROM sys_user u JOIN sys_user_role ur ON ur.user_id=u.user_id
 JOIN sys_role r ON r.role_id=ur.role_id WHERE r.role_key='admin' AND u.status='0' AND u.del_flag='0'
 HAVING COUNT(DISTINCT u.user_id)=1) a ON a.uid<>o.owner_id
WHERE @backfill_task_turn AND b.status='action' AND o.del_flag=0 AND o.status NOT IN ('done','cancelled');

INSERT IGNORE INTO jiedan_setting(setting_key,setting_value) VALUES('migration.taskTurn.v1','1');
COMMIT;
