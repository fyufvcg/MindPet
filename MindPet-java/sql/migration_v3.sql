-- Phase 3：短期记忆按 session 隔离，长期记忆加 session_id 追溯
ALTER TABLE long_term_memory ADD COLUMN IF NOT EXISTS session_id VARCHAR(128);
