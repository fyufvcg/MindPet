package service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import util.Logger;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** User-wide completed-turn history used by the memory curator. */
@Service
public class CuratorTurnStore {
    private static final int MAX_STORED_TURNS = 500;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Logger logger;
    private final Map<String, LockValue> locks = new ConcurrentHashMap<>();

    public record CompletedTurn(String turnId, String sessionId, String source,
                                String userMessage, String assistantReply, String completedAt,
                                String occurredAt, String eventTimezone) {}
    private record LockValue(String token, long expiresAt) {}

    public CuratorTurnStore(JdbcTemplate jdbc, ObjectMapper mapper, Logger logger) {
        this.jdbc = jdbc; this.mapper = mapper; this.logger = logger;
    }

    @Transactional
    public long append(String userId, String sessionId, String source, String userMessage, String assistantReply) {
        return append(userId, sessionId, source, userMessage, assistantReply, Instant.now(), ZoneId.systemDefault());
    }

    @Transactional
    public long append(String userId, String sessionId, String source, String userMessage,
                       String assistantReply, Instant occurredAt, ZoneId zone) {
        try {
            String turnId = UUID.randomUUID().toString();
            String occurred = (occurredAt == null ? Instant.now() : occurredAt).toString();
            String timezone = (zone == null ? ZoneId.systemDefault() : zone).getId();
            jdbc.update("INSERT OR IGNORE INTO curator_turns(user_id,turn_id,session_id,source,user_message,assistant_reply,completed_at,occurred_at,event_timezone) VALUES(?,?,?,?,?,?,?,?,?)",
                userId, turnId, sessionId, source, userMessage, assistantReply, Instant.now().toString(), occurred, timezone);
            Long sequence = jdbc.queryForObject("SELECT sequence FROM curator_turns WHERE turn_id=?", Long.class, turnId);
            jdbc.update("DELETE FROM curator_turns WHERE sequence IN (SELECT sequence FROM curator_turns WHERE user_id=? ORDER BY sequence DESC LIMIT -1 OFFSET ?)",
                userId, MAX_STORED_TURNS);
            return sequence == null ? 0 : sequence;
        } catch (Exception e) { logger.log("WARN", "记忆馆长回合写入失败: " + e.getMessage()); return 0; }
    }

    public long count(String userId) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM curator_turns WHERE user_id=?", Long.class, userId);
        return value == null ? 0 : value;
    }

    public long pendingCount(String userId) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM curator_turns WHERE user_id=? AND consolidation_status <> 'success'", Long.class, userId);
        return value == null ? 0 : value;
    }

    public long checkpoint(String userId) {
        List<Long> rows = jdbc.query("SELECT checkpoint FROM curator_state WHERE user_id=?", (rs, row) -> rs.getLong(1), userId);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    public void saveCheckpoint(String userId, long sequence) {
        jdbc.update("INSERT INTO curator_state(user_id,checkpoint) VALUES(?,?) ON CONFLICT(user_id) DO UPDATE SET checkpoint=excluded.checkpoint,updated_at=CURRENT_TIMESTAMP",
            userId, sequence);
    }

    public void saveCheckpoint(String userId, long sequence, String turnId) {
        jdbc.update("INSERT INTO curator_state(user_id,checkpoint,last_turn_id,last_success_at,last_error) VALUES(?,?,?,CURRENT_TIMESTAMP,'') "
                + "ON CONFLICT(user_id) DO UPDATE SET checkpoint=excluded.checkpoint,last_turn_id=excluded.last_turn_id,last_success_at=excluded.last_success_at,last_error='',updated_at=CURRENT_TIMESTAMP",
            userId, sequence, turnId);
    }

    public List<CompletedTurn> recentAt(String userId, long targetSequence, int limit) {
        List<CompletedTurn> turns = jdbc.query("SELECT turn_id,session_id,source,user_message,assistant_reply,completed_at,occurred_at,event_timezone FROM curator_turns "
                + "WHERE user_id=? AND sequence<=? ORDER BY sequence DESC LIMIT ?",
            (rs, row) -> new CompletedTurn(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)),
            userId, targetSequence, limit);
        Collections.reverse(turns);
        return turns;
    }

    public List<CompletedTurn> recentPending(String userId, int limit) {
        List<CompletedTurn> turns = jdbc.query("SELECT turn_id,session_id,source,user_message,assistant_reply,completed_at,occurred_at,event_timezone FROM curator_turns "
                + "WHERE user_id=? AND consolidation_status <> 'success' ORDER BY sequence ASC LIMIT ?",
            (rs, row) -> new CompletedTurn(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)), userId, limit);
        return turns;
    }

    public long sequenceFor(String userId, String turnId) {
        Long value = jdbc.queryForObject("SELECT sequence FROM curator_turns WHERE user_id=? AND turn_id=?",
            Long.class, userId, turnId);
        return value == null ? 0 : value;
    }

    public void recordError(String userId, String error) {
        jdbc.update("INSERT INTO curator_state(user_id,last_error,updated_at) VALUES(?,?,CURRENT_TIMESTAMP) "
                + "ON CONFLICT(user_id) DO UPDATE SET last_error=excluded.last_error,updated_at=CURRENT_TIMESTAMP",
            userId, error == null ? "" : error);
    }

    public void markProcessed(List<CompletedTurn> turns, String status) {
        if (turns == null || turns.isEmpty()) return;
        String safeStatus = status == null || status.isBlank() ? "pending" : status;
        for (CompletedTurn turn : turns) {
            jdbc.update("UPDATE curator_turns SET consolidation_status=?,processed_at=CURRENT_TIMESTAMP WHERE turn_id=?",
                safeStatus, turn.turnId());
        }
    }

    public String tryLock(String userId) {
        long now = System.currentTimeMillis();
        String token = UUID.randomUUID().toString();
        LockValue next = new LockValue(token, now + 600_000);
        LockValue value = locks.compute(userId, (key, existing) -> existing == null || existing.expiresAt < now ? next : existing);
        return value == next ? token : null;
    }

    public void unlock(String userId, String token) { locks.computeIfPresent(userId, (key, value) -> value.token.equals(token) ? null : value); }

    public void saveWorkingMemory(String userId, Object value) throws Exception {
        jdbc.update("INSERT INTO curator_state(user_id,working_memory_json) VALUES(?,?) ON CONFLICT(user_id) DO UPDATE SET working_memory_json=excluded.working_memory_json,updated_at=CURRENT_TIMESTAMP",
            userId, mapper.writeValueAsString(value));
    }

    public String getWorkingMemory(String userId) {
        List<String> rows = jdbc.query("SELECT working_memory_json FROM curator_state WHERE user_id=?", (rs, row) -> rs.getString(1), userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void recordRun(String userId, Object value) {
        try {
            jdbc.update("INSERT INTO curator_runs(user_id,payload_json,created_at) VALUES(?,?,?)", userId, mapper.writeValueAsString(value), System.currentTimeMillis());
            jdbc.update("DELETE FROM curator_runs WHERE id IN (SELECT id FROM curator_runs WHERE user_id=? ORDER BY id DESC LIMIT -1 OFFSET 100)", userId);
        } catch (Exception e) { logger.log("WARN", "记忆馆长运行记录写入失败: " + e.getMessage()); }
    }

    public List<Map<String, Object>> recentRuns(String userId, int limit) {
        List<Map<String, Object>> rows = jdbc.query("SELECT payload_json FROM curator_runs WHERE user_id=? ORDER BY id DESC LIMIT ?",
            (rs, row) -> parseMap(rs.getString(1)), userId, limit);
        Collections.reverse(rows);
        return rows;
    }

    private Map<String, Object> parseMap(String json) {
        try { return mapper.readValue(json, new TypeReference<Map<String, Object>>() {}); }
        catch (Exception e) { return Map.of(); }
    }
}
