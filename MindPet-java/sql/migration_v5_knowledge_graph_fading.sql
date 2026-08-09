-- Knowledge graph soft forgetting support.
-- Retention is calculated at query time from importance and last_seen;
-- entities, relations, and evidence are intentionally never deleted here.
CREATE INDEX IF NOT EXISTS idx_kg_entity_user_last_seen
    ON kg_entity(user_id, last_seen DESC);
CREATE INDEX IF NOT EXISTS idx_kg_relation_user_last_seen
    ON kg_relation(user_id, last_seen DESC);
