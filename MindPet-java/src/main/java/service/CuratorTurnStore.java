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
        return append(userId, UUID.randomUUID().toString(), sessionId, source,
            userMessage, assistantReply, Instant.now(), ZoneId.systemDefault());
    }

    @Transactional
    public long append(String userId, String sessionId, String source, String userMessage,
                       String assistantReply, Instant occurredAt, ZoneId zone) {
        return append(userId, UUID.randomUUID().toString(), sessionId, source,
            userMessage, assistantReply, occurredAt, zone);
    }

    @Transactional
    public long append(String userId, String turnId, String sessionId, String source,
                       String userMessage, String assistantReply, Instant occurredAt, ZoneId zone) {
        try {
            if (userId == null || userId.isBlank() || turnId == null || turnId.isBlank()) return 0;
            String occurred = (occurredAt == null ? Instant.now() : occurredAt).toString();
            String timezone = (zone == null ? ZoneId.systemDefault() : zone).getId();
            jdbc.update("INSERT OR IGNORE INTO curator_turns(user_id,turn_id,session_id,source,user_message,assistant_reply,completed_at,occurred_at,event_timezone) VALUES(?,?,?,?,?,?,?,?,?)",
                userId, turnId, sessionId, source, userMessage, assistantReply, Instant.now().toString(), occurred, timezone);
            List<Long> sequences = jdbc.query("SELECT sequence FROM curator_turns WHERE user_id=? AND turn_id=?",
                (rs, row) -> rs.getLong(1), userId, turnId);
            jdbc.update("DELETE FROM curator_turns WHERE user_id=? AND consolidation_status='success' "
                    + "AND sequence NOT IN (SELECT sequence FROM curator_turns WHERE user_id=? "
                    + "AND consolidation_status='success' ORDER BY sequence DESC LIMIT ?) "
                    + "AND NOT EXISTS (SELECT 1 FROM memory_retrieval_source s JOIN memory_retrieval_unit u ON u.id=s.unit_id "
                    + "WHERE u.user_id=curator_turns.user_id AND s.source_turn_id=curator_turns.turn_id) "
                    + "AND NOT EXISTS (SELECT 1 FROM memory_fact f WHERE f.user_id=curator_turns.user_id "
                    + "AND f.source_turn_id=curator_turns.turn_id AND f.status<>'rolled_back') "
                    + "AND NOT EXISTS (SELECT 1 FROM memory_compaction_snapshot s WHERE s.user_id=curator_turns.user_id "
                    + "AND s.entity_type='curator_turn' AND s.record_key=curator_turns.turn_id)",
                userId, userId, MAX_STORED_TURNS);
            Long sequence = sequences.isEmpty() ? null : sequences.get(0);
            return sequence == null ? 0 : sequence;
        } catch (Exception e) { logger.log("WARN", "记忆馆长回合写入失败: " + e.getMessage()); return 0; }
    }

    public long count(String userId) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM curator_turns WHERE user_id=?", Long.class, userId);
        return value == null ? 0 : value;
    }

    public long pendingCount(String userId) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM curator_turns WHERE user_id=? AND consolidation_status NOT IN ('success','partial')", Long.class, userId);
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
        jdbc.update("INSERT INTO curator_state(user_id,checkpoint,last_turn_id,last_success_at,last_error,retry_count,retry_after) VALUES(?,?,?,CURRENT_TIMESTAMP,'',0,NULL) "
                + "ON CONFLICT(user_id) DO UPDATE SET checkpoint=MAX(curator_state.checkpoint,excluded.checkpoint),last_turn_id=excluded.last_turn_id,last_success_at=excluded.last_success_at,last_error='',retry_count=0,retry_after=NULL,updated_at=CURRENT_TIMESTAMP",
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
                + "WHERE user_id=? AND consolidation_status NOT IN ('success','partial') ORDER BY sequence ASC LIMIT ?",
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
        List<Long> attempts = jdbc.query("SELECT retry_count FROM curator_state WHERE user_id=?",
            (rs, row) -> rs.getLong(1), userId);
        long currentAttempts = attempts.isEmpty() ? 0 : attempts.get(0);
        long retrySeconds = Math.min(3600L, 30L << Math.min(currentAttempts, 7L));
        String retryAfter = Instant.now().plusSeconds(retrySeconds).toString();
        jdbc.update("INSERT INTO curator_state(user_id,last_error,retry_count,retry_after,updated_at) VALUES(?,?,1,?,CURRENT_TIMESTAMP) "
                + "ON CONFLICT(user_id) DO UPDATE SET last_error=excluded.last_error,retry_count=curator_state.retry_count+1,retry_after=excluded.retry_after,updated_at=CURRENT_TIMESTAMP",
            userId, error == null ? "" : error, retryAfter);
    }

    public boolean retryAllowed(String userId) {
        List<Boolean> rows = jdbc.query("SELECT retry_after IS NULL OR julianday(retry_after)<=julianday(?) FROM curator_state WHERE user_id=?",
            (rs, row) -> rs.getBoolean(1), Instant.now().toString(), userId);
        return rows.isEmpty() || rows.get(0);
    }

    public List<String> idlePendingUsers(Instant completedBefore, int limit) {
        return jdbc.query("SELECT t.user_id FROM curator_turns t LEFT JOIN curator_state s ON s.user_id=t.user_id "
                + "WHERE t.consolidation_status NOT IN ('success','partial') AND julianday(t.completed_at)<=julianday(?) "
                + "AND (s.retry_after IS NULL OR julianday(s.retry_after)<=julianday(?)) "
                + "GROUP BY t.user_id ORDER BY MIN(t.completed_at) LIMIT ?",
            (rs, row) -> rs.getString(1), completedBefore.toString(), Instant.now().toString(), limit);
    }

    public void markProcessed(String userId, List<CompletedTurn> turns, String status) {
        if (turns == null || turns.isEmpty()) return;
        String safeStatus = status == null || status.isBlank() ? "pending" : status;
        for (CompletedTurn turn : turns) {
            jdbc.update("UPDATE curator_turns SET consolidation_status=?,processed_at=CURRENT_TIMESTAMP WHERE user_id=? AND turn_id=?",
                safeStatus, userId, turn.turnId());
        }
    }

    public void recordProposalItems(String userId, long sequence, Map<String,Object> proposal,
                                    List<Map<String,Object>> diagnostics) {
        try {
            for (String section : List.of("facts","insights","growth","retire")) {
                if (!(proposal.get(section) instanceof List<?> items)) continue;
                for (int index = 0; index < items.size(); index++) {
                    final int at = index;
                    List<Map<String,Object>> problems = diagnostics.stream()
                        .filter(d -> section.equals(d.get("section")) && ((Number)d.getOrDefault("item_index",-1)).intValue()==at).toList();
                    String payload = mapper.writeValueAsString(items.get(index));
                    // Sensitive items retain a reason and hash only; raw turns already retain their source.
                    if (MemoryContentSafety.looksSensitive(payload)) payload = "{\"redacted\":true}";
                    String key = items.get(index) instanceof Map<?,?> m && m.get("proposal_item_id") instanceof String id
                        ? id : section + ":" + sequence + ":" + index;
                    boolean terminal = !problems.isEmpty() && problems.stream().allMatch(d ->
                        String.valueOf(d.get("reason_code")).contains("sensitive") || String.valueOf(d.get("reason_code")).contains("oversized"));
                    jdbc.update("INSERT INTO curator_proposal_item(user_id,item_key,batch_sequence,section,payload_json,status,diagnostics_json) "
                        + "VALUES(?,?,?,?,?,?,?) ON CONFLICT(user_id,item_key) DO UPDATE SET payload_json=excluded.payload_json,"
                        + "status=excluded.status,diagnostics_json=excluded.diagnostics_json,attempts=curator_proposal_item.attempts+1,updated_at=CURRENT_TIMESTAMP",
                        userId,key,sequence,section,payload,problems.isEmpty()?"accepted":terminal?"rejected":"pending",mapper.writeValueAsString(problems));
                }
            }
        } catch (Exception e) { throw new IllegalStateException("保存馆长条目状态失败",e); }
    }

    public void recordAcceptedEvent(String userId, long sequence, String key, Map<String,Object> event) {
        try {
            Map<String,Object> merged=new java.util.LinkedHashMap<>(event);
            List<String> previous=jdbc.query("SELECT payload_json FROM curator_accepted_event WHERE user_id=? AND event_key=?",(rs,n)->rs.getString(1),userId,key);
            if(!previous.isEmpty()) {
                Map<String,Object> old=parseMap(previous.get(0));
                List<String> ids=new java.util.ArrayList<>(),quotes=new java.util.ArrayList<>();
                for(Map<String,Object> row:List.of(old,event)) {
                    List<?> sources=row.get("source_turn_ids") instanceof List<?> list?list:List.of();
                    List<?> evidence=row.get("evidence") instanceof List<?> list?list:List.of();
                    for(int i=0;i<sources.size();i++)if(!ids.contains(String.valueOf(sources.get(i)))) {
                        ids.add(String.valueOf(sources.get(i)));quotes.add(i<evidence.size()?String.valueOf(evidence.get(i)):"");
                    }
                }
                merged.put("source_turn_ids",ids);merged.put("evidence",quotes);
            }
            jdbc.update("INSERT INTO curator_accepted_event(user_id,event_key,batch_sequence,payload_json) VALUES(?,?,?,?) "
                +"ON CONFLICT(user_id,event_key) DO UPDATE SET payload_json=excluded.payload_json",
                userId,key,sequence,mapper.writeValueAsString(merged));
        } catch (Exception e) { throw new IllegalStateException("保存已接受馆长事件失败",e); }
    }

    public List<Map<String,Object>> acceptedEvents(String userId) {
        return jdbc.query("SELECT event_key,batch_sequence,payload_json FROM curator_accepted_event WHERE user_id=? ORDER BY batch_sequence,event_key",
            (rs,row) -> { Map<String,Object> event = new java.util.LinkedHashMap<>(parseMap(rs.getString(3)));
                event.put("event_key",rs.getString(1)); event.put("batch_sequence",rs.getLong(2)); return event; },userId);
    }

    public List<Map<String,Object>> proposalItems(String userId) {
        return jdbc.queryForList("SELECT item_key,batch_sequence,section,status,payload_json,diagnostics_json,attempts "
            + "FROM curator_proposal_item WHERE user_id=? ORDER BY batch_sequence,item_key",userId);
    }

    public List<Map<String,Object>> pendingItems(String userId) {
        return jdbc.queryForList("SELECT item_key,batch_sequence,section,payload_json,diagnostics_json FROM curator_proposal_item "
            + "WHERE user_id=? AND status='pending' ORDER BY batch_sequence,item_key",userId);
    }

    public List<String> repairUsers(int limit) {
        return jdbc.query("SELECT DISTINCT p.user_id FROM curator_proposal_item p LEFT JOIN curator_state s ON s.user_id=p.user_id "
            + "WHERE p.status='pending' AND p.attempts<3 AND (s.retry_after IS NULL OR julianday(s.retry_after)<=julianday(?)) LIMIT ?",
            (rs,row)->rs.getString(1),Instant.now().toString(),limit);
    }

    public List<Map<String,Object>> repairItems(String userId, boolean force) {
        return jdbc.queryForList("SELECT item_key,batch_sequence,section,payload_json,diagnostics_json,attempts FROM curator_proposal_item "
            + "WHERE user_id=? AND status='pending'" + (force?"":" AND attempts<3") + " ORDER BY batch_sequence,item_key",userId);
    }

    public void recordRepairFailure(String userId,long sequence) {
        jdbc.update("UPDATE curator_proposal_item SET attempts=attempts+1,updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND batch_sequence=? AND status='pending'",userId,sequence);
    }

    public void resetRepairAttempts(String userId) {
        jdbc.update("UPDATE curator_proposal_item SET attempts=0 WHERE user_id=? AND status='pending'",userId);
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
