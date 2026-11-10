SET @add_bug_column = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=database() AND table_name='jiedan_intake_receipt' AND column_name='bug_id'), 'SELECT 1', 'ALTER TABLE jiedan_intake_receipt ADD COLUMN bug_id bigint DEFAULT NULL');
PREPARE ddl FROM @add_bug_column;
EXECUTE ddl;
DEALLOCATE PREPARE ddl;
