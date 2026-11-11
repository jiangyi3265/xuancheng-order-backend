CREATE TABLE IF NOT EXISTS jiedan_project_tester (
    order_id bigint NOT NULL,
    user_id bigint NOT NULL,
    PRIMARY KEY (order_id, user_id),
    KEY idx_project_tester_user (user_id, order_id),
    FOREIGN KEY (order_id) REFERENCES jiedan_order(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=database() AND table_name='jiedan_bug' AND column_name='customer_author_id'), 'SELECT 1', 'ALTER TABLE jiedan_bug ADD customer_author_id bigint DEFAULT NULL');
PREPARE tester_stmt FROM @ddl;
EXECUTE tester_stmt;
DEALLOCATE PREPARE tester_stmt;

SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=database() AND table_name='jiedan_timeline' AND column_name='customer_author_id'), 'SELECT 1', 'ALTER TABLE jiedan_timeline ADD customer_author_id bigint DEFAULT NULL');
PREPARE tester_stmt FROM @ddl;
EXECUTE tester_stmt;
DEALLOCATE PREPARE tester_stmt;

UPDATE jiedan_bug b JOIN jiedan_intake_receipt r ON b.id=r.bug_id AND b.order_id=r.order_id
SET b.customer_author_id=r.customer_id WHERE b.customer_author_id IS NULL;
