-- Independent pre-sales quotations: safe to rerun; existing orders remain untouched.
CREATE TABLE IF NOT EXISTS jiedan_quote (
 id bigint NOT NULL AUTO_INCREMENT,
 title varchar(200) NOT NULL,
 requirement text,
 attachments longtext,
 employee_id bigint NOT NULL,
 creator_id bigint NOT NULL,
 amount decimal(12,2) DEFAULT NULL,
 quote_note text,
 status varchar(20) NOT NULL DEFAULT 'pending',
 order_id bigint DEFAULT NULL,
 version int NOT NULL DEFAULT 1,
 deleted tinyint NOT NULL DEFAULT 0,
 create_time datetime NOT NULL,
 update_time datetime NOT NULL,
 PRIMARY KEY (id),
 KEY idx_quote_employee (employee_id,deleted,status),
 KEY idx_quote_status (deleted,status,update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
