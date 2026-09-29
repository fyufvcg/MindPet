-- Long-term memory consolidation: durable facts, current profile projection,
-- and time anchored curator turns. Existing rows are preserved.

ALTER TABLE curator_state ADD COLUMN last_turn_id TEXT;
ALTER TABLE curator_state ADD COLUMN last_success_at TEXT;
ALTER TABLE curator_state ADD COLUMN last_error TEXT;
ALTER TABLE curator_turns ADD COLUMN occurred_at TEXT;
ALTER TABLE curator_turns ADD COLUMN event_timezone TEXT;
ALTER TABLE curator_turns ADD COLUMN processed_at TEXT;
ALTER TABLE curator_turns ADD COLUMN consolidation_status TEXT NOT NULL DEFAULT 'pending';

CREATE TABLE IF NOT EXISTS memory_fact (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id TEXT NOT NULL,
  predicate TEXT NOT NULL,
  value_text TEXT NOT NULL DEFAULT '',
  value_json TEXT NOT NULL DEFAULT '',
  scope TEXT NOT NULL DEFAULT 'episodic',
  assertion TEXT NOT NULL DEFAULT 'observed',
  confidence REAL NOT NULL DEFAULT 0.5,
  valid_from TEXT,
  valid_to TEXT,
  observed_at TEXT,
  event_timezone TEXT,
  raw_time_expression TEXT NOT NULL DEFAULT '',
  normalized_start TEXT,
  normalized_end TEXT,
  time_precision TEXT NOT NULL DEFAULT 'unknown',
  time_status TEXT NOT NULL DEFAULT 'unresolved',
  source_turn_id TEXT,
  raw_text TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL DEFAULT 'active',
  supersedes_id INTEGER,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(user_id, source_turn_id, predicate, value_text, normalized_start)
);

CREATE TABLE IF NOT EXISTS user_profile_current (
  user_id TEXT NOT NULL,
  slot_key TEXT NOT NULL,
  value TEXT NOT NULL,
  source_fact_id INTEGER,
  confidence REAL NOT NULL DEFAULT 0.5,
  valid_from TEXT,
  valid_to TEXT,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(user_id, slot_key)
);

CREATE INDEX IF NOT EXISTS idx_memory_fact_user_predicate_status
  ON memory_fact(user_id, predicate, status, normalized_start);
CREATE INDEX IF NOT EXISTS idx_memory_fact_user_source
  ON memory_fact(user_id, source_turn_id);
CREATE INDEX IF NOT EXISTS idx_curator_turns_pending
  ON curator_turns(user_id, consolidation_status, occurred_at);
CREATE INDEX IF NOT EXISTS idx_profile_current_user_updated
  ON user_profile_current(user_id, updated_at DESC);
