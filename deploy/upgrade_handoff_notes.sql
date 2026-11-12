-- 交接时带一句话：处理的人点「做完了」时写的说明，确认的人不用点进项目就能看到。
-- 可以重复执行。项目退回返工也记成一条交接（task_key = 'delivery'），用的是现有的 jiedan_task_turn，不需要新表。
SET @ddl = IF(EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
  AND table_name='jiedan_task_turn' AND column_name='submit_note'), 'SELECT 1',
  'ALTER TABLE jiedan_task_turn ADD COLUMN submit_note varchar(300) DEFAULT NULL');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 只恢复能从实际操作记录确认的项目验收。手动设置为待验收、没有提交人的历史项目保留原状。
START TRANSACTION;
INSERT IGNORE INTO jiedan_setting(setting_key,setting_value) VALUES ('migration.deliveryTurn.v1','1');
SET @backfill_delivery = ROW_COUNT();

INSERT IGNORE INTO jiedan_task_turn(order_id,task_key,requester_id,submitted_by,submitted_at,returned_at,return_note)
WITH reviewers AS (
    SELECT DISTINCT u.user_id FROM sys_user u
    JOIN sys_user_role ur ON ur.user_id=u.user_id JOIN sys_role r ON r.role_id=ur.role_id
    WHERE r.role_key='admin' AND r.del_flag='0' AND u.status='0' AND u.del_flag='0'
), transitions AS (
    SELECT a.id,a.target_id,a.action,a.actor_id,a.detail,a.create_time,
           ROW_NUMBER() OVER(PARTITION BY a.target_id ORDER BY a.id DESC) AS recency
    FROM jiedan_audit_log a JOIN jiedan_order o ON o.id=a.target_id
    WHERE @backfill_delivery=1 AND o.del_flag=0 AND o.status IN ('doing','review') AND a.target_type='order'
      AND (a.action IN ('order.return','order.status') OR (a.action='order.update' AND a.detail LIKE '%状态 %→%'))
), active AS (
    SELECT o.id,o.owner_id,o.status,h.actor_id,h.create_time,
           CASE WHEN o.status='doing' THEN returner.user_id
                ELSE COALESCE(previous_reviewer.user_id,creator.user_id,(SELECT MIN(user_id) FROM reviewers)) END AS reviewer,
           CASE WHEN h.action='order.return' THEN h.create_time ELSE prev.create_time END AS returned_at,
           CASE WHEN h.action='order.return' THEN h.detail ELSE prev.detail END AS why
    FROM jiedan_order o JOIN transitions h ON h.target_id=o.id AND h.recency=1
    LEFT JOIN transitions prev ON prev.target_id=o.id AND prev.recency=2 AND prev.action='order.return'
    LEFT JOIN reviewers returner ON returner.user_id=h.actor_id
    LEFT JOIN reviewers previous_reviewer ON previous_reviewer.user_id=prev.actor_id
    LEFT JOIN jiedan_audit_log created ON created.id=(
       SELECT MIN(ac.id) FROM jiedan_audit_log ac WHERE ac.target_type='order' AND ac.target_id=o.id AND ac.action='order.create')
    LEFT JOIN reviewers creator ON creator.user_id=created.actor_id
    WHERE o.owner_id IS NOT NULL AND (
       (o.status='doing' AND h.action='order.return') OR
       (o.status='review' AND h.actor_id=o.owner_id AND h.action IN ('order.status','order.update') AND h.detail LIKE '%→ 待验收%'))
)
SELECT id,'delivery',reviewer,IF(status='review',actor_id,NULL),IF(status='review',create_time,NULL),returned_at,
       LEFT(SUBSTRING_INDEX(SUBSTRING(why,CHAR_LENGTH('原因：')+1),'；改稿次数 +1',1),300)
FROM active WHERE reviewer IS NOT NULL AND reviewer<>owner_id;
COMMIT;
