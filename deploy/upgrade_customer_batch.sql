CREATE TABLE IF NOT EXISTS jiedan_customer_batch (
    creator_id bigint NOT NULL,
    request_key varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payload_hash char(64) CHARACTER SET ascii NOT NULL,
    result_json json DEFAULT NULL,
    PRIMARY KEY (creator_id, request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS jiedan_customer_name_reservation (
    user_name varchar(30) NOT NULL,
    PRIMARY KEY (user_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
