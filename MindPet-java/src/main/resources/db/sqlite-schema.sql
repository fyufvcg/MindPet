PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS schema_version (
  version INTEGER PRIMARY KEY,
  applied_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS long_term_memory (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id TEXT NOT NULL,
  session_id TEXT,
  content TEXT NOT NULL,
  role TEXT NOT NULL,
  embedding BLOB,
  importance REAL NOT NULL DEFAULT 0.5,
  confidence REAL NOT NULL DEFAULT 1.0,
  searchable INTEGER NOT NULL DEFAULT 1,
  layer INTEGER NOT NULL DEFAULT 3,
  emotion TEXT,
  event_date TEXT,
  event_at TEXT,
  event_timezone TEXT,
  event_precision TEXT,
  access_count INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_accessed TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ltm_user_created ON long_term_memory(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_ltm_user_importance ON long_term_memory(user_id, importance DESC, created_at DESC);

CREATE TABLE IF NOT EXISTS user_profile (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id TEXT NOT NULL,
  category TEXT NOT NULL,
  prop_key TEXT NOT NULL,
  prop_value TEXT NOT NULL,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(user_id, category, prop_key)
);

CREATE TABLE IF NOT EXISTS user_insight (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id TEXT NOT NULL,
  insight TEXT NOT NULL,
  context TEXT NOT NULL DEFAULT '',
  embedding BLOB,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(user_id, insight)
);

CREATE TABLE IF NOT EXISTS llm_growth (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id TEXT NOT NULL,
  category TEXT NOT NULL,
  insight TEXT NOT NULL,
  context TEXT NOT NULL DEFAULT '',
  embedding BLOB,
  title TEXT NOT NULL DEFAULT '',
  source_type TEXT NOT NULL DEFAULT '',
  source_id TEXT NOT NULL DEFAULT '',
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS sessions (
  user_id TEXT NOT NULL,
  id TEXT NOT NULL,
  name TEXT NOT NULL DEFAULT '',
  context_summary TEXT NOT NULL DEFAULT '',
  pinned INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(user_id, id)
);
CREATE INDEX IF NOT EXISTS idx_sessions_user_updated ON sessions(user_id, pinned DESC, updated_at DESC);

CREATE TABLE IF NOT EXISTS session_messages (
  user_id TEXT NOT NULL,
  session_id TEXT NOT NULL,
  message_id TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  sender TEXT NOT NULL DEFAULT '',
  content_text TEXT NOT NULL DEFAULT '',
  message_time TEXT NOT NULL DEFAULT '',
  summarized INTEGER NOT NULL DEFAULT 0,
  sequence INTEGER NOT NULL,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(user_id, session_id, message_id),
  FOREIGN KEY(user_id, session_id) REFERENCES sessions(user_id, id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_session_messages_order ON session_messages(user_id, session_id, sequence);

CREATE TABLE IF NOT EXISTS conversation_memory (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id TEXT NOT NULL,
  session_id TEXT NOT NULL DEFAULT 'default',
  payload_json TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_conversation_memory_recent ON conversation_memory(user_id, session_id, created_at DESC);

CREATE TABLE IF NOT EXISTS emotion_history (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_emotion_history_recent ON emotion_history(user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS local_cache (
  namespace TEXT NOT NULL,
  cache_key TEXT NOT NULL,
  value_json TEXT NOT NULL,
  expires_at INTEGER,
  PRIMARY KEY(namespace, cache_key)
);

CREATE TABLE IF NOT EXISTS curator_state (
  user_id TEXT PRIMARY KEY,
  checkpoint INTEGER NOT NULL DEFAULT 0,
  last_turn_id TEXT,
  last_success_at TEXT,
  last_error TEXT,
  retry_count INTEGER NOT NULL DEFAULT 0,
  retry_after TEXT,
  working_memory_json TEXT,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS curator_turns (
  sequence INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id TEXT NOT NULL,
  turn_id TEXT NOT NULL,
  session_id TEXT,
  source TEXT,
  user_message TEXT,
  assistant_reply TEXT,
  completed_at TEXT NOT NULL,
  occurred_at TEXT,
  event_timezone TEXT,
  processed_at TEXT,
  consolidation_status TEXT NOT NULL DEFAULT 'pending',
  UNIQUE(user_id, turn_id)
);
CREATE INDEX IF NOT EXISTS idx_curator_turns_user_seq ON curator_turns(user_id, sequence DESC);
CREATE INDEX IF NOT EXISTS idx_curator_turns_user_completed ON curator_turns(user_id, completed_at DESC);
CREATE TABLE IF NOT EXISTS curator_runs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  created_at INTEGER NOT NULL
);

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
  last_observed_at TEXT,
  evidence_count INTEGER NOT NULL DEFAULT 1,
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
  UNIQUE(user_id, source_turn_id, predicate, value_text, scope, assertion, normalized_start),
  FOREIGN KEY(supersedes_id) REFERENCES memory_fact(id)
);
CREATE INDEX IF NOT EXISTS idx_memory_fact_user_predicate_status
  ON memory_fact(user_id, predicate, status, normalized_start);
CREATE INDEX IF NOT EXISTS idx_memory_fact_user_source
  ON memory_fact(user_id, source_turn_id);

CREATE TABLE IF NOT EXISTS user_profile_current (
  user_id TEXT NOT NULL,
  slot_key TEXT NOT NULL,
  value TEXT NOT NULL,
  source_fact_id INTEGER,
  confidence REAL NOT NULL DEFAULT 0.5,
  valid_from TEXT,
  valid_to TEXT,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(user_id, slot_key),
  FOREIGN KEY(source_fact_id) REFERENCES memory_fact(id)
);
CREATE INDEX IF NOT EXISTS idx_profile_current_user_updated
  ON user_profile_current(user_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS memory_retrieval_unit (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  canonical_key TEXT NOT NULL,
  unit_type TEXT NOT NULL,
  predicate TEXT NOT NULL DEFAULT '',
  scope TEXT NOT NULL DEFAULT 'stable',
  status TEXT NOT NULL DEFAULT 'active',
  searchable INTEGER NOT NULL DEFAULT 1,
  content TEXT NOT NULL,
  embedding BLOB,
  token_count INTEGER NOT NULL DEFAULT 0,
  valid_from TEXT,
  valid_to TEXT,
  fact_id INTEGER,
  supersedes_unit_id TEXT,
  compaction_version INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_memory_retrieval_active_key
  ON memory_retrieval_unit(user_id, canonical_key) WHERE searchable=1 AND status='active';
CREATE INDEX IF NOT EXISTS idx_memory_retrieval_user_status
  ON memory_retrieval_unit(user_id, status, searchable, scope);
CREATE INDEX IF NOT EXISTS idx_memory_retrieval_fact
  ON memory_retrieval_unit(user_id, fact_id);

CREATE TABLE IF NOT EXISTS memory_retrieval_source (
  unit_id TEXT NOT NULL,
  source_type TEXT NOT NULL,
  source_id TEXT NOT NULL,
  source_turn_id TEXT,
  evidence_text TEXT,
  surface_value TEXT NOT NULL DEFAULT '',
  value_start INTEGER NOT NULL DEFAULT -1,
  value_end INTEGER NOT NULL DEFAULT -1,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(unit_id, source_type, source_id),
  FOREIGN KEY(unit_id) REFERENCES memory_retrieval_unit(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_memory_retrieval_source_turn
  ON memory_retrieval_source(source_turn_id);

-- Source handoff for derived memories; merge/ranking/forgetting policies stay separate.
CREATE TABLE IF NOT EXISTS memory_lifecycle (
  unit_id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  importance REAL NOT NULL DEFAULT 0.5,
  first_recorded_at TEXT,
  last_confirmed_at TEXT,
  last_used_at TEXT,
  use_count INTEGER NOT NULL DEFAULT 0,
  source_count INTEGER NOT NULL DEFAULT 0,
  pinned INTEGER NOT NULL DEFAULT 0,
  state TEXT NOT NULL DEFAULT 'live',
  original_scope TEXT,
  original_status TEXT,
  reason TEXT NOT NULL DEFAULT '',
  retired_at TEXT,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_memory_lifecycle_user ON memory_lifecycle(user_id,state);
CREATE TABLE IF NOT EXISTS memory_lifecycle_use (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  unit_id TEXT NOT NULL,
  used_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_memory_lifecycle_use_unit ON memory_lifecycle_use(unit_id,used_at);

CREATE TABLE IF NOT EXISTS curator_proposal_item (
  user_id TEXT NOT NULL,
  item_key TEXT NOT NULL,
  batch_sequence INTEGER NOT NULL,
  section TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  status TEXT NOT NULL,
  diagnostics_json TEXT NOT NULL DEFAULT '[]',
  attempts INTEGER NOT NULL DEFAULT 1,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(user_id,item_key)
);
CREATE INDEX IF NOT EXISTS idx_curator_proposal_pending ON curator_proposal_item(user_id,status,batch_sequence);
CREATE TABLE IF NOT EXISTS curator_accepted_event (
  user_id TEXT NOT NULL,
  event_key TEXT NOT NULL,
  batch_sequence INTEGER NOT NULL,
  payload_json TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(user_id,event_key)
);

CREATE TABLE IF NOT EXISTS memory_compaction_log (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id TEXT NOT NULL,
  batch_sequence INTEGER NOT NULL DEFAULT 0,
  action TEXT NOT NULL,
  unit_id TEXT,
  source_id TEXT NOT NULL DEFAULT '',
  tokens_before INTEGER NOT NULL DEFAULT 0,
  tokens_after INTEGER NOT NULL DEFAULT 0,
  reason TEXT NOT NULL DEFAULT '',
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_memory_compaction_log_user_batch
  ON memory_compaction_log(user_id, batch_sequence, id);

CREATE TABLE IF NOT EXISTS memory_compaction_batch (
  user_id TEXT NOT NULL,
  batch_sequence INTEGER NOT NULL,
  status TEXT NOT NULL DEFAULT 'applying',
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at TEXT,
  rolled_back_at TEXT,
  PRIMARY KEY(user_id, batch_sequence)
);
CREATE INDEX IF NOT EXISTS idx_memory_compaction_batch_user_status
  ON memory_compaction_batch(user_id, status, batch_sequence DESC);

CREATE TABLE IF NOT EXISTS memory_compaction_snapshot (
  user_id TEXT NOT NULL,
  batch_sequence INTEGER NOT NULL,
  entity_type TEXT NOT NULL,
  record_key TEXT NOT NULL,
  existed_before INTEGER NOT NULL DEFAULT 0,
  state_json TEXT NOT NULL DEFAULT '',
  PRIMARY KEY(user_id, batch_sequence, entity_type, record_key)
);

CREATE TABLE IF NOT EXISTS memory_corpus_migration (
  user_id TEXT PRIMARY KEY,
  migration_version INTEGER NOT NULL,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS memory_compaction_plan (
  user_id TEXT NOT NULL,
  batch_sequence INTEGER NOT NULL,
  raw_unit_id TEXT NOT NULL,
  resolved_action TEXT NOT NULL,
  target_unit_ids TEXT NOT NULL DEFAULT '[]',
  reason TEXT NOT NULL,
  applied_action TEXT NOT NULL DEFAULT 'PENDING',
  PRIMARY KEY(user_id,batch_sequence,raw_unit_id)
);
CREATE TABLE IF NOT EXISTS memory_compaction_blob (
  hash TEXT PRIMARY KEY,
  value BLOB NOT NULL
);

CREATE TABLE IF NOT EXISTS memory_gallery (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  source_type TEXT NOT NULL DEFAULT 'manual',
  source_message_id TEXT,
  session_id TEXT,
  image_uri TEXT,
  title TEXT NOT NULL DEFAULT '',
  story TEXT NOT NULL DEFAULT '',
  mood TEXT NOT NULL DEFAULT 'neutral',
  ai_summary TEXT NOT NULL DEFAULT '',
  source_context TEXT NOT NULL DEFAULT '',
  event_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(user_id, source_message_id)
);
CREATE INDEX IF NOT EXISTS idx_memory_gallery_user_event
  ON memory_gallery(user_id, event_at DESC);
CREATE INDEX IF NOT EXISTS idx_memory_gallery_user_session_created
  ON memory_gallery(user_id, session_id, created_at DESC);

CREATE TABLE IF NOT EXISTS kg_entity (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  normalized_name TEXT NOT NULL,
  display_name TEXT NOT NULL,
  entity_type TEXT NOT NULL,
  summary TEXT NOT NULL DEFAULT '',
  embedding BLOB,
  importance REAL NOT NULL DEFAULT 0.5,
  mention_count INTEGER NOT NULL DEFAULT 1,
  first_seen TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_seen TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(user_id, normalized_name, entity_type)
);
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
CREATE TABLE IF NOT EXISTS kg_relation (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  source_entity_id TEXT NOT NULL REFERENCES kg_entity(id) ON DELETE CASCADE,
  target_entity_id TEXT NOT NULL REFERENCES kg_entity(id) ON DELETE CASCADE,
  predicate TEXT NOT NULL,
  confidence REAL NOT NULL DEFAULT 0.5,
  importance REAL NOT NULL DEFAULT 0.5,
  semantic_predicate TEXT NOT NULL DEFAULT '',
  resolution_kind TEXT NOT NULL DEFAULT 'NEW',
  fact_status TEXT NOT NULL DEFAULT 'ACTIVE',
  temporal_status TEXT NOT NULL DEFAULT 'UNKNOWN',
  valid_from TEXT,
  valid_to TEXT,
  superseded_by TEXT REFERENCES kg_relation(id),
  mention_count INTEGER NOT NULL DEFAULT 1,
  first_seen TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_seen TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(user_id, source_entity_id, target_entity_id, predicate)
);
-- Additive V3 lifecycle journal. Legacy graph/API columns and retrieval schema remain compatible.
CREATE TABLE IF NOT EXISTS kg_fact_event (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  turn_hash TEXT NOT NULL,
  session_id TEXT NOT NULL,
  source_entity_id TEXT NOT NULL REFERENCES kg_entity(id) ON DELETE CASCADE,
  target_entity_id TEXT NOT NULL REFERENCES kg_entity(id) ON DELETE CASCADE,
  relation_id TEXT NOT NULL REFERENCES kg_relation(id) ON DELETE CASCADE,
  semantic_predicate TEXT NOT NULL,
  normalized_predicate TEXT NOT NULL,
  polarity TEXT NOT NULL,
  event_kind TEXT NOT NULL,
  occurred_at TEXT NOT NULL,
  confidence REAL NOT NULL,
  importance REAL NOT NULL,
  user_message TEXT NOT NULL,
  assistant_message TEXT NOT NULL DEFAULT '',
  prior_predicate TEXT NOT NULL,
  prior_semantic_predicate TEXT NOT NULL,
  UNIQUE(user_id,turn_hash,source_entity_id,target_entity_id,semantic_predicate)
);
CREATE INDEX IF NOT EXISTS idx_kg_fact_event_user_turn ON kg_fact_event(user_id,turn_hash);
CREATE TABLE IF NOT EXISTS kg_evidence (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id TEXT NOT NULL,
  turn_hash TEXT NOT NULL,
  entity_id TEXT REFERENCES kg_entity(id) ON DELETE CASCADE,
  relation_id TEXT REFERENCES kg_relation(id) ON DELETE CASCADE,
  session_id TEXT NOT NULL DEFAULT '',
  user_message TEXT NOT NULL,
  assistant_message TEXT NOT NULL DEFAULT '',
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(turn_hash, entity_id),
  UNIQUE(turn_hash, relation_id)
);
CREATE TABLE IF NOT EXISTS kg_turn_ingest (
  turn_hash TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  session_id TEXT NOT NULL DEFAULT '',
  entity_count INTEGER NOT NULL DEFAULT 0,
  relation_count INTEGER NOT NULL DEFAULT 0,
  pipeline_version TEXT NOT NULL DEFAULT 'legacy',
  memory_type TEXT NOT NULL DEFAULT 'none',
  store_decision TEXT NOT NULL DEFAULT 'UNKNOWN',
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_kg_evidence_user_created ON kg_evidence(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_kg_entity_alias_lookup ON kg_entity_alias(user_id, normalized_alias);
CREATE INDEX IF NOT EXISTS idx_kg_entity_user_importance ON kg_entity(user_id, importance DESC, last_seen DESC);
CREATE INDEX IF NOT EXISTS idx_kg_relation_user_source ON kg_relation(user_id, source_entity_id);
CREATE INDEX IF NOT EXISTS idx_kg_relation_user_target ON kg_relation(user_id, target_entity_id);

CREATE TABLE IF NOT EXISTS rpa_runs (
  id TEXT PRIMARY KEY, workflow_id TEXT, workflow_version INTEGER, session_id TEXT,
  status TEXT NOT NULL, inputs_json TEXT NOT NULL, output_json TEXT, error_json TEXT,
  created_at INTEGER NOT NULL, started_at INTEGER, finished_at INTEGER
);
CREATE TABLE IF NOT EXISTS rpa_run_events (
  id TEXT PRIMARY KEY, run_id TEXT NOT NULL REFERENCES rpa_runs(id) ON DELETE CASCADE,
  sequence INTEGER NOT NULL, type TEXT NOT NULL, action_json TEXT, payload_json TEXT,
  created_at INTEGER NOT NULL, UNIQUE(run_id, sequence)
);
CREATE TABLE IF NOT EXISTS rpa_artifacts (
  id TEXT PRIMARY KEY, run_id TEXT NOT NULL REFERENCES rpa_runs(id) ON DELETE CASCADE,
  event_id TEXT REFERENCES rpa_run_events(id) ON DELETE SET NULL,
  type TEXT NOT NULL, file_path TEXT NOT NULL, sha256 TEXT, created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_rpa_runs_workflow_created ON rpa_runs(workflow_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_rpa_events_run_seq ON rpa_run_events(run_id, sequence);
CREATE INDEX IF NOT EXISTS idx_rpa_artifacts_run ON rpa_artifacts(run_id);

INSERT OR IGNORE INTO schema_version(version) VALUES (1);
