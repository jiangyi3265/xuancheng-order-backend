CREATE TABLE IF NOT EXISTS jiedan_quote_message (
 id bigint NOT NULL AUTO_INCREMENT,
 quote_id bigint NOT NULL,
 employee_id bigint NOT NULL,
 author_id bigint NOT NULL,
 author_name varchar(128) NOT NULL,
 recipient_id bigint DEFAULT NULL,
 kind varchar(12) NOT NULL,
 content text,
 attachments mediumtext,
 amount decimal(12,2) DEFAULT NULL,
 client_key varchar(64) DEFAULT NULL,
 created_at datetime DEFAULT NULL,
 read_at datetime DEFAULT NULL,
 PRIMARY KEY (id),
 UNIQUE KEY uq_quote_message_client (quote_id,author_id,client_key),
 KEY idx_quote_message_thread (quote_id,employee_id,id),
 KEY idx_quote_message_inbox (quote_id,recipient_id,read_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- The old model retained only the latest amount and no reliable pricing timestamp.
INSERT INTO jiedan_quote_message(quote_id,employee_id,author_id,author_name,kind,content,amount,client_key)
SELECT q.id,q.employee_id,q.employee_id,COALESCE(u.nick_name,'报价人'),'price',q.quote_note,q.amount,'opening-price'
FROM jiedan_quote q LEFT JOIN sys_user u ON u.user_id=q.employee_id
WHERE q.deleted=0 AND q.amount IS NOT NULL AND NOT EXISTS
 (SELECT 1 FROM jiedan_quote_message m WHERE m.quote_id=q.id AND m.kind='price');
