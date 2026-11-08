CREATE TABLE IF NOT EXISTS jiedan_pending_receipt (
    order_id bigint NOT NULL,
    task_key varchar(32) NOT NULL,
    recipient_id bigint NOT NULL,
    first_entry bigint NOT NULL,
    sent_entry bigint DEFAULT NULL,
    sent_at datetime DEFAULT NULL,
    PRIMARY KEY (order_id, task_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
