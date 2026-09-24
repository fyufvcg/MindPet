-- ============================================================
-- MindPet 数据库初始化（Docker 首次启动自动执行）
-- ============================================================
-- 本文件只创建「后端不会自动创建」的 4 张表。
--
-- 以下表由后端启动时自动创建，此处无需重复（但为便于审阅一并列出）：
--   kg_entity / kg_relation / kg_evidence / kg_turn_ingest
--       → KnowledgeGraphService.initializeSchema()
--   rpa_runs / rpa_run_events / rpa_artifacts
--       → RpaController.initSchema() @PostConstruct
--
-- long_term_memory 的列定义全部对齐后端 PgVectorMemoryService 启动时的
-- 自动迁移（v2/v3/v6/v7），这里是严格超集，因此无需再手工执行
-- sql/migration_v*.sql。
--
-- 向量维度必须与 Embedding 模型一致：
--   bge-m3（Ollama）= 1024 维，豆包 doubao-embedding = 1024 维
-- 换模型时需同步修改本文件的 vector(1024) 并重建相关列。
-- ============================================================

CREATE EXTENSION IF NOT EXISTS vector;

-- ============ 长期记忆 ============
CREATE TABLE IF NOT EXISTS long_term_memory (
    id               BIGSERIAL PRIMARY KEY,
    user_id          VARCHAR(64)  NOT NULL,
    session_id       VARCHAR(128),
    content          TEXT         NOT NULL,
    role             VARCHAR(16),
    importance       FLOAT        DEFAULT 0.5,      -- v2
    layer            INT          DEFAULT 3,        -- v2
    emotion          VARCHAR(32),                   -- v2
    access_count     INT          DEFAULT 0,        -- v2
    last_accessed    TIMESTAMP    DEFAULT NOW(),    -- v2
    confidence       FLOAT        DEFAULT 1.0,      -- v6
    event_date       DATE,                          -- v7
    event_at         TIMESTAMP,                     -- v7
    event_timezone   VARCHAR(64),                   -- v7
    event_precision  VARCHAR(16),                   -- v7
    embedding        vector(1024),
    metadata         JSONB        DEFAULT '{}',
    created_at       TIMESTAMP    DEFAULT NOW()
);

-- 注意：故意不建 ivfflat / hnsw 索引。
-- 数据量小于约 1000 行时全表扫描更快，且 ivfflat 在空表上建索引效果差。
-- 记忆量上规模后可自行补充：
--   CREATE INDEX ON long_term_memory
--       USING hnsw (embedding vector_cosine_ops);

CREATE INDEX IF NOT EXISTS idx_memory_user       ON long_term_memory(user_id);
CREATE INDEX IF NOT EXISTS idx_memory_layer      ON long_term_memory(user_id, layer);
CREATE INDEX IF NOT EXISTS idx_memory_importance ON long_term_memory(user_id, importance);
CREATE INDEX IF NOT EXISTS idx_memory_created    ON long_term_memory(user_id, created_at DESC);

-- ============ 用户画像（KV 存储）============
-- 对应 UserProfileService：ON CONFLICT (user_id, category, prop_key) 需要唯一约束
CREATE TABLE IF NOT EXISTS user_profile (
    id         BIGSERIAL PRIMARY KEY,
    user_id    VARCHAR(64)  NOT NULL,
    category   VARCHAR(32)  NOT NULL,   -- identity / preference / experience / state
    prop_key   VARCHAR(128) NOT NULL,
    prop_value TEXT,
    updated_at TIMESTAMP    DEFAULT NOW(),
    CONSTRAINT uq_user_profile UNIQUE (user_id, category, prop_key)
);

-- ============ 相处经验（RAG）============
-- 对应 UserInsightService：ON CONFLICT (user_id, insight) 需要唯一约束
CREATE TABLE IF NOT EXISTS user_insight (
    id         BIGSERIAL PRIMARY KEY,
    user_id    VARCHAR(64)  NOT NULL,
    insight    TEXT         NOT NULL,
    context    TEXT,
    embedding  vector(1024),
    created_at TIMESTAMP    DEFAULT NOW(),
    CONSTRAINT uq_user_insight UNIQUE (user_id, insight)
);

-- ============ LLM 自我成长（RAG）============
-- 对应 UserInsightService：ON CONFLICT (user_id, category, insight) 需要唯一约束
CREATE TABLE IF NOT EXISTS llm_growth (
    id         BIGSERIAL PRIMARY KEY,
    user_id    VARCHAR(64)  NOT NULL,
    category   VARCHAR(32),
    insight    TEXT         NOT NULL,
    context    TEXT,
    embedding  vector(1024),
    created_at TIMESTAMP    DEFAULT NOW(),
    CONSTRAINT uq_llm_growth UNIQUE (user_id, category, insight)
);
