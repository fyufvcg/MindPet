-- 只清理此演示前缀，保留原有数据。
PRAGMA foreign_keys=ON;
BEGIN IMMEDIATE;
DELETE FROM kg_evidence WHERE user_id='desktop-user' AND (turn_hash LIKE 'mindpet-ui-demo-%' OR entity_id LIKE 'mindpet-ui-demo-%' OR relation_id LIKE 'mindpet-ui-demo-%');
DELETE FROM kg_relation WHERE user_id='desktop-user' AND (id LIKE 'mindpet-ui-demo-%' OR source_entity_id LIKE 'mindpet-ui-demo-%' OR target_entity_id LIKE 'mindpet-ui-demo-%');
DELETE FROM kg_entity WHERE user_id='desktop-user' AND id LIKE 'mindpet-ui-demo-%';
COMMIT;
