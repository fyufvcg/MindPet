-- Phase 2 数据库迁移：记忆分层 + 重要性 + 情感标签 + 访问统计
ALTER TABLE long_term_memory ADD COLUMN IF NOT EXISTS importance FLOAT DEFAULT 0.5;
ALTER TABLE long_term_memory ADD COLUMN IF NOT EXISTS layer INT DEFAULT 3;
ALTER TABLE long_term_memory ADD COLUMN IF NOT EXISTS emotion VARCHAR(32);
ALTER TABLE long_term_memory ADD COLUMN IF NOT EXISTS access_count INT DEFAULT 0;
ALTER TABLE long_term_memory ADD COLUMN IF NOT EXISTS last_accessed TIMESTAMP DEFAULT NOW();

-- 索引：按保留率过滤时加速
CREATE INDEX IF NOT EXISTS idx_memory_layer ON long_term_memory(user_id, layer);
CREATE INDEX IF NOT EXISTS idx_memory_importance ON long_term_memory(user_id, importance);
