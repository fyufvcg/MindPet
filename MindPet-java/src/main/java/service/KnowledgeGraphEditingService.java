package service;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Explicit user edits only. Does not change extraction, retention or retrieval policy. */
@Service
public class KnowledgeGraphEditingService {
    public record EntityInput(String label, String type, String summary, Double importance) {}
    public record RelationInput(String source, String target, String label, Double importance) {}

    private static final Set<String> TYPES = Set.of("person", "project", "technology", "tool",
        "preference", "goal", "topic", "organization", "place", "event", "other");
    private static final Set<String> PREDICATES = Set.of("prefers", "dislikes", "uses", "learns",
        "builds", "works_on", "plans", "knows", "experienced", "belongs_to", "related_to");
    private final JdbcTemplate jdbc;
    private final EmbeddingService embeddings;

    public KnowledgeGraphEditingService(JdbcTemplate jdbc, EmbeddingService embeddings) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
    }

    /** Includes faded entities so the editor can manage existing records without reviving them. */
    public List<Map<String, Object>> entities(String userId) {
        return jdbc.query("SELECT id,display_name,entity_type FROM kg_entity WHERE user_id=? "
                + "ORDER BY display_name COLLATE NOCASE,id", (rs, n) -> Map.<String, Object>of(
            "id", rs.getString("id"), "label", rs.getString("display_name"),
            "type", rs.getString("entity_type")), userId);
    }

    @Transactional
    public Map<String, Object> saveEntity(String userId, String entityId, EntityInput input) {
        if (input == null) throw bad("请填写节点信息");
        String name = text(input.label(), 256, "名称", true).replaceAll("\\s+", " ");
        String type = text(input.type(), 32, "类型", true).toLowerCase(Locale.ROOT);
        if (!TYPES.contains(type)) throw bad("请选择有效的实体类型");
        String summary = text(input.summary(), 500, "说明", false);
        double importance = score(input.importance());
        String normalized = name.toLowerCase(Locale.ROOT);
        Map<String, Object> previous = entityId == null ? null : requireEntity(userId, entityId);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM kg_entity WHERE user_id=? AND normalized_name=? "
                + "AND entity_type=? AND id<>?", Integer.class, userId, normalized, type,
                entityId == null ? "" : entityId) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已存在同名同类型节点，请编辑已有节点");
        }
        if (previous != null && "user".equals(previous.get("normalized_name"))
                && (!"user".equals(normalized) || !"person".equals(type))) {
            throw bad("中心用户的名称和类型用于识别本人，请保留 User 和人物类型");
        }
        if ("user".equals(normalized) && !"person".equals(type)) throw bad("User 节点必须使用人物类型");
        String id = entityId == null ? UUID.randomUUID().toString() : entityId;
        byte[] vector = previous == null ? null : (byte[]) previous.get("embedding");
        if (previous == null || !name.equals(previous.get("display_name"))
                || !summary.equals(previous.get("summary"))) {
            float[] values = "user".equals(normalized) ? null : embeddings.embed(name + " " + summary);
            // Never keep a stale vector after text changes, including when embedding is unavailable.
            vector = values == null ? null : VectorSearchService.encode(values);
        }
        try {
            if (previous == null) {
                jdbc.update("INSERT INTO kg_entity(id,user_id,normalized_name,display_name,entity_type,"
                        + "summary,embedding,importance) VALUES(?,?,?,?,?,?,?,?)",
                    id, userId, normalized, name, type, summary, vector, importance);
            } else {
                jdbc.update("UPDATE kg_entity SET normalized_name=?,display_name=?,entity_type=?,summary=?,"
                        + "embedding=?,importance=?,last_seen=CURRENT_TIMESTAMP WHERE user_id=? AND id=?",
                    normalized, name, type, summary, vector, importance, userId, id);
            }
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已存在同名同类型节点，请编辑已有节点");
        }
        evidence(userId, id, null, "用户手动" + (previous == null ? "创建" : "修改")
            + "节点：" + name + "。" + summary);
        return Map.of("status", "ok", "id", id);
    }

    @Transactional
    public Map<String, Object> saveRelation(String userId, String relationId, RelationInput input) {
        if (input == null) throw bad("请填写关系信息");
        String source = text(input.source(), 256, "起点", true);
        String target = text(input.target(), 256, "终点", true);
        String predicate = text(input.label(), 32, "关系", true).toLowerCase(Locale.ROOT);
        if (!PREDICATES.contains(predicate)) throw bad("请选择有效的事实关系");
        if (source.equals(target)) throw bad("请选择两个不同节点");
        Map<String, Object> from = requireEntity(userId, source);
        Map<String, Object> to = requireEntity(userId, target);
        Map<String, Object> previous = relationId == null ? null : requireRelation(userId, relationId);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM kg_relation WHERE user_id=? AND source_entity_id=? "
                + "AND target_entity_id=? AND predicate=? AND id<>?", Integer.class, userId, source,
                target, predicate, relationId == null ? "" : relationId) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "这两个节点间已存在相同关系");
        }
        double importance = score(input.importance());
        String id = relationId == null ? UUID.randomUUID().toString() : relationId;
        try {
            if (relationId == null) {
                jdbc.update("INSERT INTO kg_relation(id,user_id,source_entity_id,target_entity_id,predicate,"
                        + "confidence,importance) VALUES(?,?,?,?,?,1.0,?)",
                    id, userId, source, target, predicate, importance);
            } else {
                jdbc.update("UPDATE kg_relation SET source_entity_id=?,target_entity_id=?,predicate=?,"
                        + "confidence=1.0,importance=?,last_seen=CURRENT_TIMESTAMP WHERE user_id=? AND id=?",
                    source, target, predicate, importance, userId, id);
                // A new assertion needs new evidence; changing importance preserves original sources.
                if (!source.equals(previous.get("source_entity_id"))
                        || !target.equals(previous.get("target_entity_id"))
                        || !predicate.equals(previous.get("predicate"))) {
                    jdbc.update("DELETE FROM kg_evidence WHERE user_id=? AND relation_id=?", userId, id);
                }
            }
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "这两个节点间已存在相同关系");
        }
        evidence(userId, null, id, "用户手动确认关系：" + from.get("display_name")
            + " --" + predicate + "--> " + to.get("display_name") + "。");
        return Map.of("status", "ok", "id", id);
    }

    @Transactional
    public boolean deleteRelation(String userId, String relationId) {
        requireRelation(userId, relationId);
        jdbc.update("DELETE FROM kg_evidence WHERE user_id=? AND relation_id=?", userId, relationId);
        return jdbc.update("DELETE FROM kg_relation WHERE user_id=? AND id=?", userId, relationId) > 0;
    }

    private Map<String, Object> requireEntity(String userId, String id) {
        var rows = jdbc.queryForList("SELECT id,normalized_name,display_name,entity_type,summary,embedding "
            + "FROM kg_entity WHERE user_id=? AND id=?", userId, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "节点已不存在，请刷新星图");
        return rows.get(0);
    }

    private Map<String, Object> requireRelation(String userId, String id) {
        var rows = jdbc.queryForList("SELECT source_entity_id,target_entity_id,predicate FROM kg_relation "
            + "WHERE user_id=? AND id=?", userId, id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "关系已不存在，请刷新星图");
        }
        return rows.get(0);
    }

    private void evidence(String userId, String entityId, String relationId, String message) {
        jdbc.update("INSERT INTO kg_evidence(user_id,turn_hash,entity_id,relation_id,session_id,user_message) "
                + "VALUES(?,?,?,?,?,?)", userId, "manual:" + UUID.randomUUID(), entityId, relationId,
            "manual:knowledge-graph", message);
    }

    private static String text(String value, int max, String name, boolean required) {
        String result = value == null ? "" : value.trim();
        if (required && result.isBlank()) throw bad("请填写" + name);
        if (result.length() > max) throw bad(name + "最多 " + max + " 个字符");
        return result;
    }

    private static double score(Double value) {
        double score = value == null ? 0.65 : value;
        if (!Double.isFinite(score) || score < 0.2 || score > 1) throw bad("重要度应在 20% 至 100% 之间");
        return score;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
