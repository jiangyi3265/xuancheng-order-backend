CREATE TABLE IF NOT EXISTS jiedan_bug_reply_meta (
    update_id bigint NOT NULL,
    bug_id bigint NOT NULL,
    sender_kind varchar(12) CHARACTER SET ascii NOT NULL,
    sender_id bigint NOT NULL,
    request_key varchar(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
    payload_hash char(64) CHARACTER SET ascii DEFAULT NULL,
    PRIMARY KEY (update_id),
    UNIQUE KEY uq_bug_reply_request (bug_id, sender_id, request_key),
    KEY idx_bug_reply_team (bug_id, sender_kind, update_id),
    CONSTRAINT fk_bug_reply_meta_update FOREIGN KEY (update_id) REFERENCES jiedan_bug_update(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS jiedan_customer_issue_seen (
    customer_id bigint NOT NULL,
    bug_id bigint NOT NULL,
    update_id bigint NOT NULL,
    PRIMARY KEY (customer_id, bug_id),
    KEY idx_issue_seen_bug (bug_id),
    CONSTRAINT fk_issue_seen_bug FOREIGN KEY (bug_id) REFERENCES jiedan_bug(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
