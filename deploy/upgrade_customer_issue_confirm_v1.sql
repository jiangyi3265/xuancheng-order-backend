CREATE TABLE IF NOT EXISTS jiedan_issue_review (
    update_id bigint NOT NULL,
    bug_id bigint NOT NULL,
    customer_id bigint NOT NULL,
    sender_id bigint NOT NULL,
    request_key varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash char(64) CHARACTER SET ascii NOT NULL,
    prior_status varchar(20) NOT NULL,
    state varchar(12) CHARACTER SET ascii NOT NULL DEFAULT 'pending',
    before_image text,
    after_image text,
    note text,
    decision_hash char(64) CHARACTER SET ascii,
    PRIMARY KEY (update_id),
    UNIQUE KEY uq_issue_review_request (bug_id, sender_id, request_key),
    KEY idx_issue_review_latest (bug_id, update_id),
    CONSTRAINT fk_issue_review_update FOREIGN KEY (update_id) REFERENCES jiedan_bug_update(id) ON DELETE CASCADE,
    CONSTRAINT fk_issue_review_bug FOREIGN KEY (bug_id) REFERENCES jiedan_bug(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
