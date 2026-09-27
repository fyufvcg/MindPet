package service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import util.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Repository
public class SqliteSessionStore implements SessionStore {
    private static final int MAX_MESSAGES = 200;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Logger logger;

    public SqliteSessionStore(JdbcTemplate jdbc, ObjectMapper mapper, Logger logger) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.logger = logger;
    }

    @Override
    public List<Map<String, Object>> listSessions(String userId) {
        return jdbc.query("SELECT id,name,context_summary,pinned,created_at,updated_at FROM sessions "
                + "WHERE user_id=? ORDER BY pinned DESC,updated_at DESC LIMIT 100",
            (rs, row) -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("id", rs.getString("id"));
                result.put("name", rs.getString("name"));
                result.put("context_summary", rs.getString("context_summary"));
                result.put("pinned", rs.getInt("pinned") != 0);
                result.put("created_at", rs.getString("created_at"));
                result.put("updated_at", rs.getString("updated_at"));
                return result;
            }, userId);
    }

    @Override
    public Map<String, Object> upsertSession(String userId, String sessionId, Map<String, Object> updates) {
        String name = String.valueOf(updates.getOrDefault("name", "")).trim();
        Object summaryValue = updates.containsKey("contextSummary") ? updates.get("contextSummary") : updates.get("context_summary");
        String summary = summaryValue == null ? "" : String.valueOf(summaryValue);
        int pinned = asBoolean(updates.get("pinned")) ? 1 : 0;
        jdbc.update("INSERT INTO sessions(user_id,id,name,context_summary,pinned) VALUES(?,?,?,?,?) "
                + "ON CONFLICT(user_id,id) DO UPDATE SET "
                + "name=CASE WHEN excluded.name='' THEN sessions.name ELSE excluded.name END,"
                + "context_summary=CASE WHEN ?=0 THEN sessions.context_summary ELSE excluded.context_summary END,"
                + "pinned=CASE WHEN ?=0 THEN sessions.pinned ELSE excluded.pinned END,updated_at=CURRENT_TIMESTAMP",
            userId, sessionId, name, summary, pinned,
            updates.containsKey("contextSummary") || updates.containsKey("context_summary") ? 1 : 0,
            updates.containsKey("pinned") ? 1 : 0);
        return getMeta(userId, sessionId);
    }

    @Override @Transactional
    public void deleteSession(String userId, String sessionId) {
        jdbc.update("DELETE FROM sessions WHERE user_id=? AND id=?", userId, sessionId);
    }

    @Override @Transactional
    public void appendMessage(String userId, String sessionId, Map<String, Object> message) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>(message);
            String id = String.valueOf(payload.getOrDefault("id", "")).trim();
            if (id.isBlank()) { id = UUID.randomUUID().toString(); payload.put("id", id); }
            upsertSession(userId, sessionId, Map.of());
            Long next = jdbc.queryForObject("SELECT COALESCE(MAX(sequence),0)+1 FROM session_messages WHERE user_id=? AND session_id=?", Long.class, userId, sessionId);
            jdbc.update("INSERT INTO session_messages(user_id,session_id,message_id,payload_json,sender,content_text,message_time,summarized,sequence) "
                    + "VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(user_id,session_id,message_id) DO UPDATE SET "
                    + "payload_json=excluded.payload_json,sender=excluded.sender,content_text=excluded.content_text,"
                    + "message_time=excluded.message_time,summarized=excluded.summarized,updated_at=CURRENT_TIMESTAMP",
                userId, sessionId, id, mapper.writeValueAsString(payload), String.valueOf(payload.getOrDefault("sender", "")),
                String.valueOf(payload.getOrDefault("text", "")), String.valueOf(payload.getOrDefault("time", "")),
                asBoolean(payload.get("isSummarized")) ? 1 : 0, next == null ? 1 : next);
            jdbc.update("UPDATE sessions SET updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND id=?", userId, sessionId);
            jdbc.update("DELETE FROM session_messages WHERE rowid IN (SELECT rowid FROM session_messages "
                + "WHERE user_id=? AND session_id=? ORDER BY sequence DESC LIMIT -1 OFFSET ?)", userId, sessionId, MAX_MESSAGES);
        } catch (Exception e) {
            logger.log("ERROR", "追加会话消息失败: " + e.getMessage());
            throw new IllegalStateException(e);
        }
    }

    @Override
    public List<Map<String, Object>> loadMessages(String userId, String sessionId, int limit) {
        int safeLimit = limit <= 0 ? MAX_MESSAGES : Math.min(limit, MAX_MESSAGES);
        List<Map<String, Object>> newest = jdbc.query("SELECT payload_json FROM session_messages WHERE user_id=? AND session_id=? ORDER BY sequence DESC LIMIT ?",
            (rs, row) -> parse(rs.getString("payload_json")), userId, sessionId, safeLimit);
        java.util.Collections.reverse(newest);
        return newest;
    }

    @Override
    public List<Map<String, Object>> loadAllMessages(String userId, int limit) {
        int safeLimit = limit <= 0 ? 1000 : limit;
        return jdbc.query("SELECT payload_json FROM session_messages WHERE user_id=? ORDER BY message_time DESC,updated_at DESC LIMIT ?",
            (rs, row) -> parse(rs.getString("payload_json")), userId, safeLimit);
    }

    @Override @Transactional
    public int markMessagesSummarized(String userId, String sessionId, List<Map<String, Object>> references) {
        if (references == null || references.isEmpty()) return 0;
        Set<String> ids = new HashSet<>();
        Set<String> contentKeys = new HashSet<>();
        for (Map<String, Object> reference : references) {
            String id = String.valueOf(reference.getOrDefault("id", "")).trim();
            if (!id.isBlank()) ids.add(id);
            contentKeys.add(contentKey(reference));
        }
        int updated = 0;
        for (Map<String, Object> message : loadMessages(userId, sessionId, 0)) {
            String id = String.valueOf(message.getOrDefault("id", "")).trim();
            if (!ids.contains(id) && !contentKeys.contains(contentKey(message))) continue;
            if (asBoolean(message.get("isSummarized"))) continue;
            message.put("isSummarized", true);
            try {
                String stableId = id.isBlank() ? "legacy-" + UUID.nameUUIDFromBytes((sessionId + "|" + contentKey(message)).getBytes(StandardCharsets.UTF_8)) : id;
                message.put("id", stableId);
                jdbc.update("UPDATE session_messages SET payload_json=?,summarized=1,updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND session_id=? AND message_id=?",
                    mapper.writeValueAsString(message), userId, sessionId, stableId);
                updated++;
            } catch (Exception e) { logger.log("WARN", "标记消息摘要失败: " + e.getMessage()); }
        }
        return updated;
    }

    private Map<String, Object> getMeta(String userId, String sessionId) {
        List<Map<String, Object>> rows = jdbc.query("SELECT id,name,context_summary,pinned,created_at,updated_at FROM sessions WHERE user_id=? AND id=?",
            (rs, row) -> {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("id", rs.getString("id")); result.put("name", rs.getString("name"));
                result.put("context_summary", rs.getString("context_summary")); result.put("pinned", rs.getInt("pinned") != 0);
                result.put("created_at", rs.getString("created_at")); result.put("updated_at", rs.getString("updated_at"));
                return result;
            }, userId, sessionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> parse(String json) {
        try { return mapper.readValue(json, new TypeReference<Map<String, Object>>() {}); }
        catch (Exception e) { return new LinkedHashMap<>(); }
    }
    private String contentKey(Map<String, Object> message) { return String.valueOf(message.getOrDefault("sender", "")) + "|" + String.valueOf(message.getOrDefault("text", "")); }
    private boolean asBoolean(Object value) { return value instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(value)) || "1".equals(String.valueOf(value)); }
}
