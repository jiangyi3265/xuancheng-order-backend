SET @ddl = IF(EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='jiedan_order' AND column_name='urgent_until'),
    'SELECT 1', 'ALTER TABLE jiedan_order ADD COLUMN urgent_until datetime DEFAULT NULL COMMENT ''项目加急截止时间（北京时间）''');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
