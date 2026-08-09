CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS kg_entity (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    normalized_name VARCHAR(256) NOT NULL,
    display_name VARCHAR(256) NOT NULL,
    entity_type VARCHAR(32) NOT NULL,
    summary TEXT NOT NULL DEFAULT '',
    embedding vector(1024),
    importance DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    mention_count INTEGER NOT NULL DEFAULT 1,
    first_seen TIMESTAMP NOT NULL DEFAULT NOW(),
    last_seen TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, normalized_name, entity_type)
);

CREATE TABLE IF NOT EXISTS kg_relation (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    source_entity_id VARCHAR(36) NOT NULL REFERENCES kg_entity(id) ON DELETE CASCADE,
    target_entity_id VARCHAR(36) NOT NULL REFERENCES kg_entity(id) ON DELETE CASCADE,
    predicate VARCHAR(48) NOT NULL,
    confidence DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    importance DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    mention_count INTEGER NOT NULL DEFAULT 1,
    first_seen TIMESTAMP NOT NULL DEFAULT NOW(),
    last_seen TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (user_id, source_entity_id, target_entity_id, predicate)
);

CREATE TABLE IF NOT EXISTS kg_evidence (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    turn_hash VARCHAR(64) NOT NULL,
    entity_id VARCHAR(36) REFERENCES kg_entity(id) ON DELETE CASCADE,
    relation_id VARCHAR(36) REFERENCES kg_relation(id) ON DELETE CASCADE,
    session_id VARCHAR(256) NOT NULL DEFAULT '',
    user_message TEXT NOT NULL,
    assistant_message TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (turn_hash, entity_id),
    UNIQUE (turn_hash, relation_id)
);

CREATE TABLE IF NOT EXISTS kg_turn_ingest (
    turn_hash VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(256) NOT NULL DEFAULT '',
    entity_count INTEGER NOT NULL DEFAULT 0,
    relation_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_kg_entity_user_importance
    ON kg_entity(user_id, importance DESC, last_seen DESC);
CREATE INDEX IF NOT EXISTS idx_kg_relation_user_source
    ON kg_relation(user_id, source_entity_id);
CREATE INDEX IF NOT EXISTS idx_kg_relation_user_target
    ON kg_relation(user_id, target_entity_id);
CREATE INDEX IF NOT EXISTS idx_kg_evidence_entity
    ON kg_evidence(entity_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_kg_evidence_relation
    ON kg_evidence(relation_id, created_at DESC);
