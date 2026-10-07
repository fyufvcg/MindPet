-- MindPet V3 memory-write metadata. All changes are additive and legacy readers remain valid.
ALTER TABLE kg_relation ADD COLUMN semantic_predicate TEXT NOT NULL DEFAULT '';
ALTER TABLE kg_relation ADD COLUMN resolution_kind TEXT NOT NULL DEFAULT 'NEW';
ALTER TABLE kg_relation ADD COLUMN fact_status TEXT NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE kg_relation ADD COLUMN temporal_status TEXT NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE kg_relation ADD COLUMN valid_from TEXT;
ALTER TABLE kg_relation ADD COLUMN valid_to TEXT;
ALTER TABLE kg_relation ADD COLUMN superseded_by TEXT;

ALTER TABLE kg_turn_ingest ADD COLUMN pipeline_version TEXT NOT NULL DEFAULT 'legacy';
ALTER TABLE kg_turn_ingest ADD COLUMN memory_type TEXT NOT NULL DEFAULT 'none';
ALTER TABLE kg_turn_ingest ADD COLUMN store_decision TEXT NOT NULL DEFAULT 'UNKNOWN';

CREATE TABLE IF NOT EXISTS kg_entity_alias (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id TEXT NOT NULL,
    entity_id TEXT NOT NULL REFERENCES kg_entity(id) ON DELETE CASCADE,
    normalized_alias TEXT NOT NULL,
    display_alias TEXT NOT NULL,
    alias_source TEXT NOT NULL DEFAULT 'explicit',
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, entity_id, normalized_alias)
);
CREATE INDEX IF NOT EXISTS idx_kg_entity_alias_lookup
    ON kg_entity_alias(user_id, normalized_alias);
CREATE INDEX IF NOT EXISTS idx_kg_relation_user_fact_status
    ON kg_relation(user_id, fact_status, last_seen DESC);
