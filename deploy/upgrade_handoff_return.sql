SET @ddl = IF(EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
  AND table_name='jiedan_task_turn' AND column_name='returned_at'), 'SELECT 1',
  'ALTER TABLE jiedan_task_turn ADD COLUMN returned_at datetime DEFAULT NULL');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
  AND table_name='jiedan_task_turn' AND column_name='return_note'), 'SELECT 1',
  'ALTER TABLE jiedan_task_turn ADD COLUMN return_note varchar(300) DEFAULT NULL');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
  AND table_name='jiedan_urgent_handoff' AND column_name='returned_at'), 'SELECT 1',
  'ALTER TABLE jiedan_urgent_handoff ADD COLUMN returned_at datetime DEFAULT NULL');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
  AND table_name='jiedan_urgent_handoff' AND column_name='return_note'), 'SELECT 1',
  'ALTER TABLE jiedan_urgent_handoff ADD COLUMN return_note varchar(300) DEFAULT NULL');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
