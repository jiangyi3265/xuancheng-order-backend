-- 旧项目默认未处理，只有首次手动切换后才会产生处理时间。重复执行不覆盖已有标记。
SET @ddl = IF(EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='jiedan_order' AND column_name='handled'),
    'SELECT 1', 'ALTER TABLE jiedan_order ADD COLUMN handled tinyint(1) NOT NULL DEFAULT 0 COMMENT ''内部手动标记：0未处理 1已处理''');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='jiedan_order' AND column_name='handled_time'),
    'SELECT 1', 'ALTER TABLE jiedan_order ADD COLUMN handled_time datetime DEFAULT NULL COMMENT ''最近一次实际切换处理状态的时间''');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
