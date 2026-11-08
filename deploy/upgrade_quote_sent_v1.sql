SET @ddl_quote_sent = IF(EXISTS (
 SELECT 1 FROM information_schema.columns WHERE table_schema=database()
 AND table_name='jiedan_quote' AND column_name='last_sent_time'
), 'SELECT 1', 'ALTER TABLE jiedan_quote ADD COLUMN last_sent_time datetime DEFAULT NULL');
PREPARE quote_sent_upgrade FROM @ddl_quote_sent;
EXECUTE quote_sent_upgrade;
DEALLOCATE PREPARE quote_sent_upgrade;

UPDATE jiedan_quote q SET last_sent_time=COALESCE((
 SELECT MAX(a.create_time) FROM jiedan_audit_log a
 WHERE a.target_type='quote' AND a.target_id=q.id
 AND (a.action='quote.assign' OR (a.action='quote.edit' AND a.detail='更新需求并邀请报价'))
),q.create_time)
WHERE last_sent_time IS NULL;

SET @ddl_quote_source = IF(EXISTS (
 SELECT 1 FROM information_schema.columns WHERE table_schema=database()
 AND table_name='jiedan_quote' AND column_name='source_name'
), 'SELECT 1', 'ALTER TABLE jiedan_quote ADD COLUMN source_name varchar(20) DEFAULT NULL, ADD COLUMN source_seq int unsigned DEFAULT NULL, ADD UNIQUE KEY uq_quote_source_seq(source_name,source_seq)');
PREPARE quote_source_upgrade FROM @ddl_quote_source;
EXECUTE quote_source_upgrade;
DEALLOCATE PREPARE quote_source_upgrade;

CREATE TABLE IF NOT EXISTS jiedan_quote_counter (
 source_name varchar(20) NOT NULL,
 last_number int unsigned NOT NULL,
 PRIMARY KEY(source_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Recognize unambiguous existing titles such as “谢谢 2”, including deleted numbers.
DROP TEMPORARY TABLE IF EXISTS quote_number_import;
CREATE TEMPORARY TABLE quote_number_import AS
SELECT min(id) quote_id, regexp_replace(compact_title,'[0-9]+$','') source_name,
       cast(regexp_substr(compact_title,'[0-9]+$') as unsigned) source_seq
FROM (SELECT id, regexp_replace(trim(title),'[[:space:]]+','') compact_title FROM jiedan_quote WHERE source_name IS NULL) candidates
WHERE compact_title regexp '^(小慧|谢谢|硕硕|杨|小松|圈圈)[1-9][0-9]{0,8}$'
GROUP BY source_name,source_seq HAVING count(*)=1;
UPDATE jiedan_quote q JOIN quote_number_import n ON n.quote_id=q.id
LEFT JOIN jiedan_quote used ON used.source_name=n.source_name AND used.source_seq=n.source_seq
SET q.source_name=n.source_name,q.source_seq=n.source_seq WHERE q.source_name IS NULL AND used.id IS NULL;
DROP TEMPORARY TABLE quote_number_import;

INSERT INTO jiedan_quote_counter(source_name,last_number)
SELECT source_name,max(source_seq) FROM jiedan_quote WHERE source_name IS NOT NULL GROUP BY source_name
ON DUPLICATE KEY UPDATE last_number=greatest(last_number,values(last_number));
