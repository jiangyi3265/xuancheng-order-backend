CREATE TABLE IF NOT EXISTS jiedan_intake_receipt (
    customer_id bigint NOT NULL,
    request_key varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_hash char(64) CHARACTER SET ascii NOT NULL,
    order_id bigint DEFAULT NULL,
    PRIMARY KEY (customer_id, request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户需求提交回执';
