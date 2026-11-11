CREATE TABLE IF NOT EXISTS jiedan_agent_link (
  order_id bigint NOT NULL,
  token_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_by bigint NOT NULL,
  created_at datetime NOT NULL,
  expires_at datetime NOT NULL,
  last_read_at datetime DEFAULT NULL,
  disabled tinyint NOT NULL DEFAULT 0,
  read_window varchar(450) CHARACTER SET ascii NOT NULL DEFAULT '',
  PRIMARY KEY (order_id),
  UNIQUE KEY uq_agent_token (token_hash),
  FOREIGN KEY (order_id) REFERENCES jiedan_order(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=database() AND table_name='jiedan_bug' AND column_name='sender_kind'), 'SELECT 1', 'ALTER TABLE jiedan_bug ADD sender_kind varchar(12) DEFAULT NULL');
PREPARE sheet_stmt FROM @ddl;
EXECUTE sheet_stmt;
DEALLOCATE PREPARE sheet_stmt;
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=database() AND table_name='jiedan_bug' AND column_name='sheet_chars'), 'SELECT 1', 'ALTER TABLE jiedan_bug ADD sheet_chars int DEFAULT NULL');
PREPARE sheet_stmt FROM @ddl;
EXECUTE sheet_stmt;
DEALLOCATE PREPARE sheet_stmt;

-- Old customer intake receipts identify the sender even when the project was subsequently rebound.
-- Never rewrite the original issue, author or attachments.
UPDATE jiedan_bug b JOIN jiedan_intake_receipt r ON r.bug_id=b.id AND r.order_id=b.order_id
LEFT JOIN sys_user u ON u.user_id=r.customer_id
SET b.sender_kind=IF(b.created_by=u.user_name, 'old_account', 'old_intake')
WHERE b.sender_kind IS NULL;
