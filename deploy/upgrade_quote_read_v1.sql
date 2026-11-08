SET @quote_ddl = IF(EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='jiedan_quote' AND column_name='notice_version'
), 'SELECT 1', 'ALTER TABLE jiedan_quote ADD COLUMN notice_version int NOT NULL DEFAULT 1');
PREPARE quote_upgrade FROM @quote_ddl;
EXECUTE quote_upgrade;
DEALLOCATE PREPARE quote_upgrade;

CREATE TABLE IF NOT EXISTS jiedan_quote_read (
    quote_id bigint NOT NULL,
    member_id bigint NOT NULL,
    read_version int NOT NULL,
    PRIMARY KEY (quote_id, member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
