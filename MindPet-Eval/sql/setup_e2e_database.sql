\set ON_ERROR_STOP on

-- Run as a PostgreSQL administrator. Supply the password through a psql variable:
--   psql ... --set=e2e_db_password="<temporary secret>" --file=...
-- The password is intentionally not stored in this repository.
\if :{?e2e_db_password}
\else
  \echo 'ERROR: e2e_db_password psql variable is required'
  \quit 3
\endif

SELECT 'CREATE ROLE mindpet_e2e_runner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION'
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'mindpet_e2e_runner')
\gexec

ALTER ROLE mindpet_e2e_runner WITH
    LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION
    PASSWORD :'e2e_db_password';

SELECT 'CREATE DATABASE mindpet_e2e_eval OWNER postgres'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'mindpet_e2e_eval')
\gexec

-- Remove any direct privileges accidentally granted to the experiment role elsewhere.
SELECT format('REVOKE ALL PRIVILEGES ON DATABASE %I FROM mindpet_e2e_runner', datname)
FROM pg_database
WHERE datname IN ('mindpet', 'mindpet_eval')
\gexec

GRANT CONNECT ON DATABASE mindpet_e2e_eval TO mindpet_e2e_runner;

\connect mindpet_e2e_eval

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS long_term_memory (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(128),
    content TEXT NOT NULL,
    role VARCHAR(32) NOT NULL DEFAULT 'user',
    importance DOUBLE PRECISION DEFAULT 0.5,
    confidence DOUBLE PRECISION DEFAULT 1.0,
    layer INTEGER DEFAULT 3,
    emotion VARCHAR(32),
    embedding vector(1024),
    metadata JSONB DEFAULT '{}'::jsonb,
    event_date DATE,
    event_at TIMESTAMP,
    event_timezone VARCHAR(64),
    event_precision VARCHAR(16),
    created_at TIMESTAMP DEFAULT NOW(),
    last_accessed TIMESTAMP DEFAULT NOW(),
    access_count INTEGER DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_memory_user_created
    ON long_term_memory(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_memory_layer
    ON long_term_memory(user_id, layer);
CREATE INDEX IF NOT EXISTS idx_memory_importance
    ON long_term_memory(user_id, importance);

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
CREATE INDEX IF NOT EXISTS idx_kg_entity_user_last_seen
    ON kg_entity(user_id, last_seen DESC);
CREATE INDEX IF NOT EXISTS idx_kg_relation_user_source
    ON kg_relation(user_id, source_entity_id);
CREATE INDEX IF NOT EXISTS idx_kg_relation_user_target
    ON kg_relation(user_id, target_entity_id);
CREATE INDEX IF NOT EXISTS idx_kg_relation_user_last_seen
    ON kg_relation(user_id, last_seen DESC);
CREATE INDEX IF NOT EXISTS idx_kg_evidence_entity
    ON kg_evidence(entity_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_kg_evidence_relation
    ON kg_evidence(relation_id, created_at DESC);

REVOKE ALL ON SCHEMA public FROM mindpet_e2e_runner;
GRANT USAGE ON SCHEMA public TO mindpet_e2e_runner;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO mindpet_e2e_runner;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO mindpet_e2e_runner;

ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO mindpet_e2e_runner;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO mindpet_e2e_runner;

SELECT current_database() AS database,
       current_user AS provisioning_user,
       has_database_privilege('mindpet_e2e_runner', current_database(), 'CONNECT') AS runner_can_connect;
