-- Unified asynchronous memory evaluation metadata.
-- Defaults keep existing rows and legacy/manual inserts readable.
ALTER TABLE long_term_memory
    ADD COLUMN IF NOT EXISTS importance FLOAT DEFAULT 0.5;

ALTER TABLE long_term_memory
    ADD COLUMN IF NOT EXISTS confidence FLOAT DEFAULT 1.0;

ALTER TABLE long_term_memory
    ADD COLUMN IF NOT EXISTS session_id VARCHAR(128);

UPDATE long_term_memory SET importance = 0.5 WHERE importance IS NULL;
UPDATE long_term_memory SET confidence = 1.0 WHERE confidence IS NULL;

CREATE INDEX IF NOT EXISTS idx_memory_importance
    ON long_term_memory(user_id, importance);
