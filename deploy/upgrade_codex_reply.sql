CREATE TABLE IF NOT EXISTS jiedan_agent_reply_key (
 order_id bigint NOT NULL PRIMARY KEY,
 secret_hash char(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL UNIQUE,
 reader_id bigint DEFAULT NULL,
 read_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 FOREIGN KEY (order_id) REFERENCES jiedan_order(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
