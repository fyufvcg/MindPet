package service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tool.ToolUserContext;
import util.Logger;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Seven-day short context stored in embedded SQLite. */
@Service
public class ConversationMemoryService {
    private static final long TTL_MILLIS = Duration.ofDays(7).toMillis();
    private static final int MAX_SIZE = 200;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Logger logger;

    private record StoredMemoryRow(long id, Map<String, Object> payload) {}

    public ConversationMemoryService(JdbcTemplate jdbc, ObjectMapper mapper, Logger logger) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.logger = logger;
    }

    public void append(String userId, Map<String, Object> message) {
        if (userId == null || userId.isBlank() || message == null) return;
        try {
            long now = System.currentTimeMillis();
            String sessionId = sessionId();
            jdbc.update("INSERT INTO conversation_memory(user_id,session_id,payload_json,created_at,expires_at) VALUES(?,?,?,?,?)",
                userId, sessionId, mapper.writeValueAsString(message), now, now + TTL_MILLIS);
            prune(userId, sessionId, now);
        } catch (Exception e) { logger.log("WARN", "短期记忆写入失败: " + e.getMessage()); }
    }

    /** Insert or replace a streaming turn message without adding a database row for every token. */
    public void upsert(String userId, Map<String, Object> message) {
        if (userId == null || userId.isBlank() || message == null) return;
        String messageId = String.valueOf(message.getOrDefault("id", "")).trim();
        if (messageId.isEmpty()) {
            append(userId, message);
            return;
        }
        try {
            long now = System.currentTimeMillis();
            String sessionId = sessionId();
            List<StoredMemoryRow> recent = jdbc.query(
                "SELECT id,payload_json FROM conversation_memory WHERE user_id=? AND session_id=? ORDER BY id DESC LIMIT ?",
                (rs, row) -> new StoredMemoryRow(rs.getLong("id"), parse(rs.getString("payload_json"))),
                userId, sessionId, MAX_SIZE);
            Long existingId = recent.stream()
                .filter(row -> messageId.equals(String.valueOf(row.payload().getOrDefault("id", ""))))
                .map(StoredMemoryRow::id)
                .findFirst()
                .orElse(null);
            String payload = mapper.writeValueAsString(message);
            if (existingId == null) {
                jdbc.update("INSERT INTO conversation_memory(user_id,session_id,payload_json,created_at,expires_at) VALUES(?,?,?,?,?)",
                    userId, sessionId, payload, now, now + TTL_MILLIS);
            } else {
                // Keep the original row order; only refresh its content and seven-day expiry.
                jdbc.update("UPDATE conversation_memory SET payload_json=?,expires_at=? WHERE id=?",
                    payload, now + TTL_MILLIS, existingId);
            }
            prune(userId, sessionId, now);
        } catch (Exception e) { logger.log("WARN", "短期记忆更新失败: " + e.getMessage()); }
    }

    private void prune(String userId, String sessionId, long now) {
        jdbc.update("DELETE FROM conversation_memory WHERE expires_at IS NOT NULL AND expires_at<?", now);
        jdbc.update("DELETE FROM conversation_memory WHERE id IN (SELECT id FROM conversation_memory WHERE user_id=? AND session_id=? ORDER BY id DESC LIMIT -1 OFFSET ?)",
            userId, sessionId, MAX_SIZE);
    }

    public List<Map<String, Object>> loadRecent(String userId, int limit) {
        if (userId == null || userId.isBlank() || limit <= 0) return Collections.emptyList();
        List<Map<String, Object>> rows = jdbc.query("SELECT payload_json FROM conversation_memory WHERE user_id=? AND session_id=? "
                + "AND (expires_at IS NULL OR expires_at>?) ORDER BY id DESC LIMIT ?",
            (rs, row) -> parse(rs.getString("payload_json")), userId, sessionId(), System.currentTimeMillis(), limit);
        Collections.reverse(rows);
        return rows;
    }

    public void clear(String userId) {
        if (userId != null && !userId.isBlank()) jdbc.update("DELETE FROM conversation_memory WHERE user_id=? AND session_id=?", userId, sessionId());
    }

    public List<Map<String, Object>> loadRecentAllSessions(String userId, int limit) {
        if (userId == null || userId.isBlank() || limit <= 0) return List.of();
        return jdbc.query("SELECT payload_json FROM conversation_memory WHERE user_id=? AND (expires_at IS NULL OR expires_at>?) ORDER BY created_at DESC LIMIT ?",
            (rs, row) -> parse(rs.getString("payload_json")), userId, System.currentTimeMillis(), limit);
    }

    private String sessionId() {
        String value = ToolUserContext.getSessionId();
        return value == null || value.isBlank() ? "default" : value;
    }

    private Map<String, Object> parse(String json) {
        try { return mapper.readValue(json, new TypeReference<Map<String, Object>>() {}); }
        catch (Exception e) { logger.log("WARN", "跳过损坏的短期记忆: " + e.getMessage()); return Map.of(); }
    }
}
