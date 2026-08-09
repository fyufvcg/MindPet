-- Demonstration graph for desktop-user. All demo entities use a demo: normalized_name.
-- Cleanup later with:
-- DELETE FROM kg_entity WHERE user_id = 'desktop-user' AND normalized_name LIKE 'demo:%';
-- DELETE FROM kg_turn_ingest WHERE user_id = 'desktop-user' AND session_id LIKE 'demo-session-%';

BEGIN;

INSERT INTO kg_entity (id, user_id, normalized_name, display_name, entity_type, summary, importance, mention_count)
VALUES
  ('10000000-0000-0000-0000-000000000001', 'desktop-user', 'demo:user', '演示用户', 'person', '演示数据：正在构建本地优先的个人 AI 助手。', 1.00, 8),
  ('10000000-0000-0000-0000-000000000002', 'desktop-user', 'demo:agentpet', 'AgentPet 桌面助手', 'project', '演示数据：带持久会话、文件和长期记忆的桌面 AI 助手。', 0.93, 7),
  ('10000000-0000-0000-0000-000000000003', 'desktop-user', 'demo:postgresql', 'PostgreSQL', 'technology', '演示数据：长期记忆、知识图谱和向量检索的持久存储。', 0.89, 6),
  ('10000000-0000-0000-0000-000000000004', 'desktop-user', 'demo:redis', 'Redis', 'technology', '演示数据：短期会话缓存和上下文恢复。', 0.74, 5),
  ('10000000-0000-0000-0000-000000000005', 'desktop-user', 'demo:rag', 'RAG 语义检索', 'technology', '演示数据：根据语义匹配召回与当前问题相关的知识。', 0.91, 7),
  ('10000000-0000-0000-0000-000000000006', 'desktop-user', 'demo:knowledge-graph', '知识图谱可视化', 'project', '演示数据：将用户确认的事实与语义关联展示为可探索网络。', 0.95, 8),
  ('10000000-0000-0000-0000-000000000007', 'desktop-user', 'demo:frontend', '交互式前端工作台', 'goal', '演示数据：在前端中展示节点、关系、来源对话与语义相似边。', 0.83, 6)
ON CONFLICT (user_id, normalized_name, entity_type) DO NOTHING;

-- Six deterministic 1024-dimensional demo vectors produce three dashed semantic pairs:
-- PostgreSQL/Redis, RAG/Knowledge graph, and AgentPet/Frontend.
WITH vector_values(id, x1, x2, x3, x4) AS (
  VALUES
    ('10000000-0000-0000-0000-000000000002', 0.0, 0.0, 1.0, 0.0),
    ('10000000-0000-0000-0000-000000000003', 1.0, 0.0, 0.0, 0.0),
    ('10000000-0000-0000-0000-000000000004', 0.9, 0.435889894, 0.0, 0.0),
    ('10000000-0000-0000-0000-000000000005', 0.0, 1.0, 0.0, 0.0),
    ('10000000-0000-0000-0000-000000000006', 0.0, 0.9, 0.435889894, 0.0),
    ('10000000-0000-0000-0000-000000000007', 0.0, 0.0, 0.9, 0.435889894)
), vectors AS (
  SELECT id,
    ('[' || string_agg(
      CASE n
        WHEN 1 THEN x1::text
        WHEN 2 THEN x2::text
        WHEN 3 THEN x3::text
        WHEN 4 THEN x4::text
        ELSE '0'
      END,
      ',' ORDER BY n
    ) || ']')::vector AS embedding
  FROM vector_values CROSS JOIN generate_series(1, 1024) AS n
  GROUP BY id, x1, x2, x3, x4
)
UPDATE kg_entity AS entity
SET embedding = vectors.embedding
FROM vectors
WHERE entity.id = vectors.id;

INSERT INTO kg_relation (id, user_id, source_entity_id, target_entity_id, predicate, confidence, importance, mention_count)
VALUES
  ('20000000-0000-0000-0000-000000000001', 'desktop-user', '10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002', 'uses', 0.98, 0.90, 4),
  ('20000000-0000-0000-0000-000000000002', 'desktop-user', '10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000005', 'learns', 0.95, 0.86, 3),
  ('20000000-0000-0000-0000-000000000003', 'desktop-user', '10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000006', 'builds', 0.99, 0.97, 5),
  ('20000000-0000-0000-0000-000000000004', 'desktop-user', '10000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000007', 'works_on', 0.96, 0.84, 3),
  ('20000000-0000-0000-0000-000000000005', 'desktop-user', '10000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000003', 'uses', 0.98, 0.88, 4),
  ('20000000-0000-0000-0000-000000000006', 'desktop-user', '10000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000004', 'uses', 0.96, 0.76, 3),
  ('20000000-0000-0000-0000-000000000007', 'desktop-user', '10000000-0000-0000-0000-000000000006', '10000000-0000-0000-0000-000000000003', 'uses', 0.97, 0.90, 4),
  ('20000000-0000-0000-0000-000000000008', 'desktop-user', '10000000-0000-0000-0000-000000000006', '10000000-0000-0000-0000-000000000005', 'uses', 0.98, 0.94, 5),
  ('20000000-0000-0000-0000-000000000009', 'desktop-user', '10000000-0000-0000-0000-000000000007', '10000000-0000-0000-0000-000000000006', 'related_to', 0.93, 0.79, 2)
ON CONFLICT (user_id, source_entity_id, target_entity_id, predicate) DO NOTHING;

INSERT INTO kg_turn_ingest (turn_hash, user_id, session_id, entity_count, relation_count)
VALUES
  (repeat('a', 63) || '1', 'desktop-user', 'demo-session-persistence', 4, 3),
  (repeat('b', 63) || '2', 'desktop-user', 'demo-session-knowledge', 2, 4),
  (repeat('c', 63) || '3', 'desktop-user', 'demo-session-frontend', 1, 2)
ON CONFLICT (turn_hash) DO NOTHING;

INSERT INTO kg_evidence (user_id, turn_hash, entity_id, relation_id, session_id, user_message, assistant_message)
VALUES
  ('desktop-user', repeat('a', 63) || '1', '10000000-0000-0000-0000-000000000001', NULL, 'demo-session-persistence', '我正在做一个叫 AgentPet 的桌面助手，希望会话、生成文件和用户知识在电脑重启后都能保留。长期记忆和知识图谱用 PostgreSQL，Redis 做短期会话缓存。', '已记录：AgentPet 采用 PostgreSQL 持久化长期数据，并以 Redis 承担短期会话缓存。'),
  ('desktop-user', repeat('a', 63) || '1', '10000000-0000-0000-0000-000000000002', NULL, 'demo-session-persistence', '我正在做一个叫 AgentPet 的桌面助手，希望会话、生成文件和用户知识在电脑重启后都能保留。长期记忆和知识图谱用 PostgreSQL，Redis 做短期会话缓存。', '已记录：AgentPet 采用 PostgreSQL 持久化长期数据，并以 Redis 承担短期会话缓存。'),
  ('desktop-user', repeat('a', 63) || '1', '10000000-0000-0000-0000-000000000003', NULL, 'demo-session-persistence', '我正在做一个叫 AgentPet 的桌面助手，希望会话、生成文件和用户知识在电脑重启后都能保留。长期记忆和知识图谱用 PostgreSQL，Redis 做短期会话缓存。', '已记录：AgentPet 采用 PostgreSQL 持久化长期数据，并以 Redis 承担短期会话缓存。'),
  ('desktop-user', repeat('a', 63) || '1', '10000000-0000-0000-0000-000000000004', NULL, 'demo-session-persistence', '我正在做一个叫 AgentPet 的桌面助手，希望会话、生成文件和用户知识在电脑重启后都能保留。长期记忆和知识图谱用 PostgreSQL，Redis 做短期会话缓存。', '已记录：AgentPet 采用 PostgreSQL 持久化长期数据，并以 Redis 承担短期会话缓存。'),
  ('desktop-user', repeat('b', 63) || '2', '10000000-0000-0000-0000-000000000005', NULL, 'demo-session-knowledge', '我想让 Agent 在聊天过程中提取我的项目、偏好和目标，做成可视化知识图谱；查询时还要结合 RAG 语义检索。', '已记录：图谱保存事实关系，RAG 负责按语义挑选相关知识。'),
  ('desktop-user', repeat('b', 63) || '2', '10000000-0000-0000-0000-000000000006', NULL, 'demo-session-knowledge', '我想让 Agent 在聊天过程中提取我的项目、偏好和目标，做成可视化知识图谱；查询时还要结合 RAG 语义检索。', '已记录：图谱保存事实关系，RAG 负责按语义挑选相关知识。'),
  ('desktop-user', repeat('c', 63) || '3', '10000000-0000-0000-0000-000000000007', NULL, 'demo-session-frontend', '前端希望有网络感：实线表示可靠事实，虚线表示语义相似；点击节点时能看到来自哪段对话。', '已记录：前端将展示事实边、语义边和可追溯的来源对话。'),
  ('desktop-user', repeat('a', 63) || '1', NULL, '20000000-0000-0000-0000-000000000001', 'demo-session-persistence', '我正在做一个叫 AgentPet 的桌面助手，希望会话、生成文件和用户知识在电脑重启后都能保留。长期记忆和知识图谱用 PostgreSQL，Redis 做短期会话缓存。', '已记录：AgentPet 采用 PostgreSQL 持久化长期数据，并以 Redis 承担短期会话缓存。'),
  ('desktop-user', repeat('a', 63) || '1', NULL, '20000000-0000-0000-0000-000000000005', 'demo-session-persistence', '我正在做一个叫 AgentPet 的桌面助手，希望会话、生成文件和用户知识在电脑重启后都能保留。长期记忆和知识图谱用 PostgreSQL，Redis 做短期会话缓存。', '已记录：AgentPet 采用 PostgreSQL 持久化长期数据，并以 Redis 承担短期会话缓存。'),
  ('desktop-user', repeat('a', 63) || '1', NULL, '20000000-0000-0000-0000-000000000006', 'demo-session-persistence', '我正在做一个叫 AgentPet 的桌面助手，希望会话、生成文件和用户知识在电脑重启后都能保留。长期记忆和知识图谱用 PostgreSQL，Redis 做短期会话缓存。', '已记录：AgentPet 采用 PostgreSQL 持久化长期数据，并以 Redis 承担短期会话缓存。'),
  ('desktop-user', repeat('b', 63) || '2', NULL, '20000000-0000-0000-0000-000000000002', 'demo-session-knowledge', '我想让 Agent 在聊天过程中提取我的项目、偏好和目标，做成可视化知识图谱；查询时还要结合 RAG 语义检索。', '已记录：图谱保存事实关系，RAG 负责按语义挑选相关知识。'),
  ('desktop-user', repeat('b', 63) || '2', NULL, '20000000-0000-0000-0000-000000000003', 'demo-session-knowledge', '我想让 Agent 在聊天过程中提取我的项目、偏好和目标，做成可视化知识图谱；查询时还要结合 RAG 语义检索。', '已记录：图谱保存事实关系，RAG 负责按语义挑选相关知识。'),
  ('desktop-user', repeat('b', 63) || '2', NULL, '20000000-0000-0000-0000-000000000007', 'demo-session-knowledge', '我想让 Agent 在聊天过程中提取我的项目、偏好和目标，做成可视化知识图谱；查询时还要结合 RAG 语义检索。', '已记录：图谱保存事实关系，RAG 负责按语义挑选相关知识。'),
  ('desktop-user', repeat('b', 63) || '2', NULL, '20000000-0000-0000-0000-000000000008', 'demo-session-knowledge', '我想让 Agent 在聊天过程中提取我的项目、偏好和目标，做成可视化知识图谱；查询时还要结合 RAG 语义检索。', '已记录：图谱保存事实关系，RAG 负责按语义挑选相关知识。'),
  ('desktop-user', repeat('c', 63) || '3', NULL, '20000000-0000-0000-0000-000000000004', 'demo-session-frontend', '前端希望有网络感：实线表示可靠事实，虚线表示语义相似；点击节点时能看到来自哪段对话。', '已记录：前端将展示事实边、语义边和可追溯的来源对话。'),
  ('desktop-user', repeat('c', 63) || '3', NULL, '20000000-0000-0000-0000-000000000009', 'demo-session-frontend', '前端希望有网络感：实线表示可靠事实，虚线表示语义相似；点击节点时能看到来自哪段对话。', '已记录：前端将展示事实边、语义边和可追溯的来源对话。')
ON CONFLICT DO NOTHING;

COMMIT;

SELECT 'entities=' || COUNT(*) FROM kg_entity WHERE user_id = 'desktop-user';
SELECT 'relations=' || COUNT(*) FROM kg_relation WHERE user_id = 'desktop-user';
SELECT 'evidence=' || COUNT(*) FROM kg_evidence WHERE user_id = 'desktop-user';
SELECT s.display_name || ' --' || r.predicate || '--> ' || t.display_name
FROM kg_relation r
JOIN kg_entity s ON s.id = r.source_entity_id
JOIN kg_entity t ON t.id = r.target_entity_id
WHERE r.user_id = 'desktop-user'
ORDER BY r.importance DESC;
