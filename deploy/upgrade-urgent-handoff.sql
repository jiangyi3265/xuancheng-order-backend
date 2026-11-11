CREATE TABLE IF NOT EXISTS jiedan_urgent_handoff (
  order_id bigint NOT NULL PRIMARY KEY,
  requester_id bigint NOT NULL,
  due_at datetime NOT NULL,
  submitted_by bigint DEFAULT NULL,
  submitted_at datetime DEFAULT NULL,
  CONSTRAINT fk_urgent_handoff_order FOREIGN KEY (order_id) REFERENCES jiedan_order(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Recover the sender only when the last recorded urgency change matches the active deadline.
INSERT IGNORE INTO jiedan_urgent_handoff (order_id, requester_id, due_at)
SELECT o.id, a.actor_id, o.urgent_until
FROM jiedan_order o
JOIN jiedan_audit_log a ON a.id = (
  SELECT MAX(h.id) FROM jiedan_audit_log h
  WHERE h.target_type='order' AND h.target_id=o.id AND h.action='order.update'
    AND (h.detail LIKE '加急至 %' OR h.detail='取消加急')
)
WHERE o.urgent_until IS NOT NULL AND o.handled=0 AND o.del_flag=0
  AND o.status NOT IN ('done','cancelled') AND a.actor_role='boss'
  AND a.actor_id <> o.owner_id AND a.detail=CONCAT('加急至 ',DATE_FORMAT(o.urgent_until,'%Y-%m-%d %H:%i'));
