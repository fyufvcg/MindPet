package service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import util.Logger;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Maintains the compact, auditable corpus that is used for default memory retrieval. */
@Service
public class MemoryCorpusCompactionService {

    private static final int DEFAULT_TOKEN_BUDGET = 512;
    private static final int MAX_CURATOR_CONTEXT_UNITS = 40;
    private static final ObjectMapper SNAPSHOT_MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final VectorSearchService vectorSearch;
    private final Logger logger;
    private static final int CORPUS_MIGRATION_VERSION = 3;
    private static final int ROLLBACK_BATCHES = 20;
    private static final double MAX_VECTOR_DISTANCE = 0.65;

    public record RetrievalUnit(String id, String canonicalKey, String unitType, String predicate, String value,
                                String scope, String status, String assertion, String content, int tokenCount,
                                String validFrom, String validTo, String normalizedStart, String timeStatus,
                                List<String> sourceTurnIds, double score) {
        public RetrievalUnit { sourceTurnIds = List.copyOf(sourceTurnIds); }
    }

    public MemoryCorpusCompactionService(JdbcTemplate jdbc, VectorSearchService vectorSearch, Logger logger) {
        this.jdbc = jdbc;
        this.vectorSearch = vectorSearch;
        this.logger = logger;
    }

    /** Starts a durable undo journal inside the curator commit transaction. */
    public void beginBatch(String userId, long batchSequence) {
        if (userId == null || userId.isBlank() || batchSequence <= 0) {
            throw new IllegalArgumentException("压缩批次缺少有效用户或序号");
        }
        List<String> existing = jdbc.query("SELECT status FROM memory_compaction_batch "
                + "WHERE user_id=? AND batch_sequence=?", (rs, row) -> rs.getString(1), userId, batchSequence);
        if (existing.isEmpty()) {
            jdbc.update("INSERT INTO memory_compaction_batch(user_id,batch_sequence,status) VALUES(?,?,'applying')",
                userId, batchSequence);
            return;
        }
        if ("committed".equals(existing.get(0)) && Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM curator_proposal_item WHERE user_id=? AND batch_sequence=? AND status='pending')",
                Boolean.class,userId,batchSequence))) {
            jdbc.update("UPDATE memory_compaction_batch SET status='applying' WHERE user_id=? AND batch_sequence=?",userId,batchSequence);
            return;
        }
        if (!"rolled_back".equals(existing.get(0))) {
            throw new IllegalStateException("压缩批次序号已使用，不能重复提交: " + batchSequence);
        }
        jdbc.update("DELETE FROM memory_compaction_snapshot WHERE user_id=? AND batch_sequence=?", userId, batchSequence);
        jdbc.update("UPDATE memory_compaction_batch SET status='applying',created_at=CURRENT_TIMESTAMP,"
                + "completed_at=NULL,rolled_back_at=NULL WHERE user_id=? AND batch_sequence=?",
            userId, batchSequence);
    }

    public void completeBatch(String userId, long batchSequence) {
        int changed = jdbc.update("UPDATE memory_compaction_batch SET status='committed',completed_at=CURRENT_TIMESTAMP "
                + "WHERE user_id=? AND batch_sequence=? AND status='applying'", userId, batchSequence);
        if (changed != 1) throw new IllegalStateException("馆长压缩批次状态异常，无法提交回滚清单");
        jdbc.update("UPDATE memory_compaction_batch SET status='archived' WHERE user_id=? AND status='committed' "
            + "AND batch_sequence NOT IN (SELECT batch_sequence FROM memory_compaction_batch WHERE user_id=? "
            + "AND status='committed' ORDER BY batch_sequence DESC LIMIT ?)", userId, userId, ROLLBACK_BATCHES);
        jdbc.update("DELETE FROM memory_compaction_snapshot WHERE user_id=? AND batch_sequence IN ("
            + "SELECT batch_sequence FROM memory_compaction_batch WHERE user_id=? AND status='archived')", userId, userId);
        jdbc.update("DELETE FROM memory_compaction_blob WHERE NOT EXISTS (SELECT 1 FROM memory_compaction_snapshot s "
            + "WHERE INSTR(s.state_json,memory_compaction_blob.hash)>0)");
    }

    /** Restores the prior retrieval state for the latest committed curator batch. */
    @Transactional
    public void rollbackBatch(String userId, long batchSequence) {
        List<String> states = jdbc.query("SELECT status FROM memory_compaction_batch "
                + "WHERE user_id=? AND batch_sequence=?", (rs, row) -> rs.getString(1), userId, batchSequence);
        if (states.isEmpty() || !"committed".equals(states.get(0))) {
            throw new IllegalStateException("只能回滚已提交且尚未回滚的馆长批次");
        }
        Boolean laterBatch = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM memory_compaction_batch "
                + "WHERE user_id=? AND batch_sequence>? AND status IN ('applying','committed'))",
            Boolean.class, userId, batchSequence);
        if (Boolean.TRUE.equals(laterBatch)) {
            throw new IllegalStateException("必须先回滚该用户更新的馆长批次");
        }
        List<Map<String, Object>> snapshots = jdbc.queryForList(
            "SELECT entity_type,record_key,existed_before,state_json FROM memory_compaction_snapshot "
                + "WHERE user_id=? AND batch_sequence=? "
                + "ORDER BY CASE WHEN existed_before=0 THEN 0 ELSE 1 END,entity_type,record_key DESC",
            userId, batchSequence);
        for (Map<String, Object> snapshot : snapshots) {
            restoreSnapshot(userId, text(snapshot.get("entity_type")), text(snapshot.get("record_key")),
                number(snapshot.get("existed_before")) != 0, text(snapshot.get("state_json")));
        }
        jdbc.update("DELETE FROM curator_accepted_event WHERE user_id=? AND batch_sequence=?",userId,batchSequence);
        jdbc.update("UPDATE curator_proposal_item SET status='pending',attempts=0 WHERE user_id=? AND batch_sequence=?",userId,batchSequence);
        jdbc.update("UPDATE memory_compaction_batch SET status='rolled_back',rolled_back_at=CURRENT_TIMESTAMP "
                + "WHERE user_id=? AND batch_sequence=? AND status='committed'", userId, batchSequence);
        log(userId, batchSequence, "ROLLBACK", "", Long.toString(batchSequence), 0, 0,
            "restored_previous_compaction_state");
    }

    /** Captures the fact slot and profile projection before deterministic version reconciliation. */
    public void snapshotFactSlot(String userId, String predicate, String scope, long batchSequence) {
        List<Map<String, Object>> facts = jdbc.queryForList("SELECT id,status,supersedes_id,valid_from,valid_to,observed_at,confidence,assertion,"
                + "last_observed_at,evidence_count,updated_at "
                + "FROM memory_fact WHERE user_id=? AND predicate=? AND scope=?", userId, predicate, scope);
        for (Map<String, Object> fact : facts) {
            remember(userId, batchSequence, "memory_fact", Long.toString(number(fact.get("id"))),
                "rolled_back".equals(text(fact.get("status"))) ? null : fact);
        }
        snapshotProfileSlot(userId, predicate, batchSequence);
    }

    public void snapshotProfileSlot(String userId, String slot, long batchSequence) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT user_id,slot_key,value,source_fact_id,confidence,"
                + "valid_from,valid_to,updated_at FROM user_profile_current WHERE user_id=? AND slot_key=?",
            userId, slot);
        remember(userId, batchSequence, "user_profile_current", slot, rows.isEmpty() ? null : rows.get(0));
    }

    public void snapshotProcessingState(String userId, List<CuratorTurnStore.CompletedTurn> turns, long sequence) {
        List<Map<String, Object>> states = jdbc.queryForList("SELECT * FROM curator_state WHERE user_id=?", userId);
        remember(userId, sequence, "curator_state", userId, states.isEmpty() ? null : states.get(0));
        for (CuratorTurnStore.CompletedTurn turn : turns) {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT consolidation_status,processed_at FROM curator_turns "
                + "WHERE user_id=? AND turn_id=?", userId, turn.turnId());
            if (!rows.isEmpty()) remember(userId, sequence, "curator_turn", turn.turnId(), rows.get(0));
        }
    }

    /** Working memory is a projection of live state, independent of query ranking. */
    public List<RetrievalUnit> currentWorkingFacts(String userId) {
        return jdbc.query("SELECT u.id FROM memory_retrieval_unit u JOIN memory_fact f ON f.id=u.fact_id "
                + "WHERE u.user_id=? AND u.searchable=1 AND u.status='active' AND f.assertion<>'negated' "
                + "AND ((f.predicate='current_project' AND f.scope='current' AND f.status='active') "
                + "OR (f.scope='planned' AND f.assertion='planned' AND f.status='proposed')) ORDER BY f.id DESC",
            (rs, row) -> toRetrievalUnit(loadCandidate(userId, rs.getString(1))), userId);
    }

    public void recordInsertedFact(String userId, long factId, long batchSequence) {
        remember(userId, batchSequence, "memory_fact", Long.toString(factId), null);
    }

    public void snapshotInsight(String userId, String insight, long batchSequence) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,user_id,insight,context,embedding,created_at "
                + "FROM user_insight WHERE user_id=? AND insight=?", userId, insight);
        remember(userId, batchSequence, "user_insight", insight, rows.isEmpty() ? null : rows.get(0));
    }

    public void snapshotGrowth(String userId, String category, String insight, long batchSequence) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,user_id,category,insight,context,embedding,"
                + "title,source_type,source_id,created_at,updated_at FROM llm_growth "
                + "WHERE user_id=? AND category=? AND insight=?", userId, category, insight);
        remember(userId, batchSequence, "llm_growth", compositeKey(category, insight),
            rows.isEmpty() ? null : rows.get(0));
    }

    /** Indexes existing structured memories once so upgrades do not leave users with an empty corpus. */
    @Transactional
    public void ensureLegacyIndexed(String userId) {
        if (userId == null || userId.isBlank()) return;
        Integer version = jdbc.query("SELECT migration_version FROM memory_corpus_migration WHERE user_id=?",
            rs -> rs.next() ? rs.getInt(1) : 0, userId);
        boolean initializeStructured = version == null || version < CORPUS_MIGRATION_VERSION;
        try {
            indexUnmappedLongTermMemories(userId);
            backfillRawSources(userId);
            if (!initializeStructured) { reconcileEditedSources(userId); return; }

            mergeLegacyDuplicateFacts(userId);
            List<Long> factIds = jdbc.query("SELECT id FROM memory_fact WHERE user_id=? AND status NOT IN ('rolled_back','merged_duplicate') ORDER BY id",
                (rs, row) -> rs.getLong(1), userId);
            for (long factId : factIds) syncFact(userId, factId, null, 0);
            reconcileFactStatuses(userId, 0);

            Set<String> projectedSlots = new HashSet<>();
            List<Map<String, Object>> projected = jdbc.queryForList(
                "SELECT slot_key,value,source_fact_id,valid_from,valid_to FROM user_profile_current WHERE user_id=?",
                userId);
            for (Map<String, Object> row : projected) {
                String slot = text(row.get("slot_key"));
                projectedSlots.add(slot);
                if (number(row.get("source_fact_id")) > 0) continue;
                upsertTextUnit(userId, "profile", slot, "stable", slot + ": " + text(row.get("value")),
                    null, null, text(row.get("valid_from")), text(row.get("valid_to")),
                    "user_profile_current", slot, null, "legacy_profile", 0);
            }

            List<Map<String, Object>> profiles = jdbc.queryForList(
                "SELECT id,category,prop_key,prop_value,updated_at FROM user_profile WHERE user_id=? ORDER BY updated_at DESC",
                userId);
            for (Map<String, Object> row : profiles) {
                String key = text(row.get("prop_key"));
                if (projectedSlots.contains(key)) continue;
                String category = text(row.get("category"));
                upsertTextUnit(userId, "profile", category + ":" + key, "stable",
                    key + ": " + text(row.get("prop_value")), null, null, "", "",
                    "user_profile", text(row.get("id")), null, "legacy_profile", 0);
            }

            List<Map<String, Object>> insights = jdbc.queryForList(
                "SELECT id,insight,context,embedding FROM user_insight WHERE user_id=? ORDER BY created_at DESC", userId);
            for (Map<String, Object> row : insights) {
                upsertTextUnit(userId, "insight", text(row.get("insight")), "stable", text(row.get("insight")),
                    bytes(row.get("embedding")), null, "", "", "user_insight", text(row.get("id")),
                    text(row.get("context")), "legacy_insight", 0);
            }

            List<Map<String, Object>> growth = jdbc.queryForList(
                "SELECT id,category,insight,context,embedding FROM llm_growth WHERE user_id=? ORDER BY updated_at DESC,created_at DESC",
                userId);
            for (Map<String, Object> row : growth) {
                String category = text(row.get("category"));
                upsertTextUnit(userId, "growth", category + ":" + text(row.get("insight")), "stable",
                    "[" + category + "] " + text(row.get("insight")), bytes(row.get("embedding")), null,
                    "", "", "llm_growth", text(row.get("id")), text(row.get("context")),
                    "legacy_growth", 0);
            }
            jdbc.update("INSERT INTO memory_corpus_migration(user_id,migration_version) VALUES(?,?) "
                + "ON CONFLICT(user_id) DO UPDATE SET migration_version=excluded.migration_version,updated_at=CURRENT_TIMESTAMP",
                userId, CORPUS_MIGRATION_VERSION);
            reconcileEditedSources(userId);
        } catch (RuntimeException error) {
            logger.log("WARN", "初始化馆长检索语料失败，保留现有记忆检索：" + error.getMessage());
            throw error;
        }
    }

    private void mergeLegacyDuplicateFacts(String userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM memory_fact WHERE user_id=? "
            + "AND status IN ('active','proposed') ORDER BY COALESCE(last_observed_at,observed_at) DESC,id DESC", userId);
        Map<String, Map<String, Object>> keepers = new LinkedHashMap<>();
        for (Map<String, Object> fact : rows) {
            String scope = text(fact.get("scope"));
            String key = text(fact.get("predicate")) + "|" + normalize(text(fact.get("value_text"))) + "|" + scope + "|"
                + (Set.of("observed", "confirmed").contains(text(fact.get("assertion"))) ? "affirmative" : text(fact.get("assertion")))
                + (Set.of("current", "stable").contains(scope) ? "" : "|" + text(fact.get("normalized_start")));
            Map<String, Object> keeper = keepers.putIfAbsent(key, fact);
            if (keeper == null) continue;
            // A different value between observations represents a separate valid interval.
            if (MemoryFactOntology.isProjectable(text(fact.get("predicate")), scope, text(fact.get("assertion")))) {
                Boolean intervening = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM memory_fact WHERE user_id=? "
                    + "AND predicate=? AND scope=? AND value_text<>? AND COALESCE(last_observed_at,observed_at)>? "
                    + "AND COALESCE(last_observed_at,observed_at)<?)", Boolean.class, userId, fact.get("predicate"), scope,
                    fact.get("value_text"), fact.get("observed_at"), keeper.get("observed_at"));
                if (Boolean.TRUE.equals(intervening)) continue;
            }
            long keepId = number(keeper.get("id"));
            long duplicateId = number(fact.get("id"));
            jdbc.update("UPDATE memory_fact SET status='merged_duplicate',updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND id=?", userId, duplicateId);
            jdbc.update("UPDATE memory_retrieval_unit SET status='merged_duplicate',searchable=0 WHERE user_id=? AND fact_id=?", userId, duplicateId);
            syncFact(userId, keepId, null, 0);
            String keepUnit = unitIdForFact(userId, keepId);
            String duplicateUnit = unitIdForFact(userId, duplicateId);
            for (Map<String, Object> source : jdbc.queryForList("SELECT * FROM memory_retrieval_source WHERE unit_id=?", duplicateUnit)) {
                addSource(keepUnit, text(source.get("source_type")), text(source.get("source_id")),
                    text(source.get("source_turn_id")), text(source.get("evidence_text")));
            }
            addSource(keepUnit, "curator_turn", text(fact.get("source_turn_id")), text(fact.get("source_turn_id")), text(fact.get("raw_text")));
            jdbc.update("UPDATE memory_fact SET confidence=MAX(confidence,?),valid_from=CASE WHEN valid_from IS NULL "
                + "OR (? IS NOT NULL AND ?<valid_from) THEN ? ELSE valid_from END,updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND id=?",
                fact.get("confidence"), fact.get("valid_from"), fact.get("valid_from"), fact.get("valid_from"), userId, keepId);
            jdbc.update("UPDATE memory_fact SET evidence_count=(SELECT COUNT(DISTINCT source_turn_id) FROM memory_retrieval_source "
                + "WHERE unit_id=? AND source_type='curator_turn') WHERE user_id=? AND id=?", keepUnit, userId, keepId);
            syncFact(userId, keepId, null, 0);
        }
    }

    /** Sources may arrive after raw indexing. Missing provenance never authorizes retirement. */
    private void backfillRawSources(String userId) {
        jdbc.update("UPDATE memory_retrieval_source SET source_turn_id=(SELECT ct.turn_id FROM curator_turns ct "
            + "JOIN long_term_memory m ON CAST(m.id AS TEXT)=memory_retrieval_source.source_id "
            + "WHERE m.user_id=? AND ct.user_id=m.user_id AND ct.session_id=m.session_id AND ct.user_message=m.content "
            + "ORDER BY ct.sequence DESC LIMIT 1) WHERE source_type='long_term_memory' "
            + "AND (source_turn_id IS NULL OR source_turn_id='') AND unit_id IN (SELECT id FROM memory_retrieval_unit WHERE user_id=?)",
            userId, userId);
        // Recover units hidden by the old provenance-only migration. Deliberate retirement remains intact.
        jdbc.update("UPDATE memory_retrieval_unit SET searchable=1,status='active',updated_at=CURRENT_TIMESTAMP "
            + "WHERE user_id=? AND status='inactive' AND searchable=0 AND EXISTS (SELECT 1 FROM memory_compaction_log l "
            + "WHERE l.unit_id=memory_retrieval_unit.id AND l.user_id=? AND l.reason='source_turn_unavailable') "
            + "AND NOT EXISTS (SELECT 1 FROM memory_compaction_log l WHERE l.unit_id=memory_retrieval_unit.id "
            + "AND l.user_id=? AND l.reason<>'source_turn_unavailable' AND l.action IN ('MERGE','RETIRE'))",
            userId, userId, userId);
    }

    private void reconcileEditedSources(String userId) {
        // Invalidate stale projections; the source remains available through the raw fallback.
        jdbc.update("UPDATE memory_retrieval_unit SET searchable=0,status='stale',updated_at=CURRENT_TIMESTAMP "
            + "WHERE user_id=? AND status='active' AND unit_type IN ('profile','insight','growth','raw_memory') "
            + "AND EXISTS (SELECT 1 FROM memory_retrieval_source s WHERE s.unit_id=memory_retrieval_unit.id AND ("
            + "(s.source_type='long_term_memory' AND NOT EXISTS (SELECT 1 FROM long_term_memory m WHERE m.user_id=? "
            + "AND CAST(m.id AS TEXT)=s.source_id AND m.searchable=1 AND m.content=memory_retrieval_unit.content)) OR "
            + "(s.source_type='user_insight' AND NOT EXISTS (SELECT 1 FROM user_insight i WHERE i.user_id=? "
            + "AND CAST(i.id AS TEXT)=s.source_id AND i.insight=memory_retrieval_unit.content)) OR "
            + "(s.source_type='user_profile_current' AND NOT EXISTS (SELECT 1 FROM user_profile_current p "
            + "WHERE p.user_id=? AND p.slot_key=s.source_id AND memory_retrieval_unit.content=p.slot_key||': '||p.value)) OR "
            + "(s.source_type='user_profile' AND NOT EXISTS (SELECT 1 FROM user_profile p WHERE p.user_id=? "
            + "AND CAST(p.id AS TEXT)=s.source_id AND memory_retrieval_unit.content=p.prop_key||': '||p.prop_value "
            + "AND NOT EXISTS (SELECT 1 FROM user_profile_current c WHERE c.user_id=p.user_id AND c.slot_key=p.prop_key))) OR "
            + "(s.source_type='llm_growth' AND NOT EXISTS (SELECT 1 FROM llm_growth g WHERE g.user_id=? "
            + "AND CAST(g.id AS TEXT)=s.source_id AND memory_retrieval_unit.content='['||g.category||'] '||g.insight))))",
            userId, userId, userId, userId, userId, userId);
        for (Map<String, Object> source : jdbc.queryForList("SELECT id,content,embedding,session_id FROM long_term_memory m "
                + "WHERE user_id=? AND searchable=1 AND EXISTS (SELECT 1 FROM memory_retrieval_source s "
                + "JOIN memory_retrieval_unit u ON u.id=s.unit_id WHERE s.source_type='long_term_memory' "
                + "AND s.source_id=CAST(m.id AS TEXT) AND u.status='stale')", userId)) {
            upsertTextUnit(userId, "raw_memory", text(source.get("content")), "episodic", text(source.get("content")),
                bytes(source.get("embedding")), null, "", "", "long_term_memory", text(source.get("id")), null, "edited_raw", 0);
        }
        for (Map<String, Object> row : jdbc.queryForList("SELECT id,category,prop_key,prop_value FROM user_profile p WHERE user_id=? "
                + "AND NOT EXISTS (SELECT 1 FROM user_profile_current c WHERE c.user_id=p.user_id AND c.slot_key=p.prop_key)", userId)) {
            upsertTextUnit(userId, "profile", text(row.get("category")) + ":" + text(row.get("prop_key")), "stable",
                text(row.get("prop_key")) + ": " + text(row.get("prop_value")), null, null, "", "", "user_profile",
                text(row.get("id")), null, "edited_profile", 0);
        }
        for (Map<String, Object> row : jdbc.queryForList("SELECT slot_key,value,source_fact_id,valid_from,valid_to "
                + "FROM user_profile_current WHERE user_id=? AND source_fact_id IS NULL", userId)) {
            String slot = text(row.get("slot_key"));
            upsertTextUnit(userId, "profile", slot, "stable", slot + ": " + text(row.get("value")),
                null, null, text(row.get("valid_from")), text(row.get("valid_to")), "user_profile_current", slot,
                null, "edited_current_profile", 0);
        }
        for (Map<String, Object> row : jdbc.queryForList("SELECT id,insight,context,embedding FROM user_insight WHERE user_id=?", userId)) {
            upsertTextUnit(userId, "insight", text(row.get("insight")), "stable", text(row.get("insight")), bytes(row.get("embedding")),
                null, "", "", "user_insight", text(row.get("id")), text(row.get("context")), "edited_insight", 0);
        }
        for (Map<String, Object> row : jdbc.queryForList("SELECT id,category,insight,context,embedding FROM llm_growth WHERE user_id=?", userId)) {
            upsertTextUnit(userId, "growth", text(row.get("category")) + ":" + text(row.get("insight")), "stable",
                "[" + text(row.get("category")) + "] " + text(row.get("insight")), bytes(row.get("embedding")),
                null, "", "", "llm_growth", text(row.get("id")), text(row.get("context")), "edited_growth", 0);
        }
        backfillRawSources(userId);
    }

    private void indexUnmappedLongTermMemories(String userId) {
        List<Map<String, Object>> memories = jdbc.queryForList(
            "SELECT m.id,m.session_id,m.content,m.embedding FROM long_term_memory m WHERE m.user_id=? AND m.searchable=1 "
                + "AND NOT EXISTS (SELECT 1 FROM memory_retrieval_source s "
                + "WHERE s.source_type='long_term_memory' AND s.source_id=CAST(m.id AS TEXT)) "
                + "ORDER BY m.created_at DESC", userId);
        for (Map<String, Object> memory : memories) {
            String id = text(memory.get("id"));
            String content = text(memory.get("content"));
            if (MemoryContentSafety.looksSensitive(content)) {
                int changed = jdbc.update("UPDATE long_term_memory SET searchable=0 "
                        + "WHERE user_id=? AND id=? AND searchable=1", userId, id);
                if (changed > 0) log(userId, 0, "RETIRE", "", id, tokenCount(content), 0,
                    "sensitive_legacy_memory");
                continue;
            }
            // A new source gets a new identity when an older equal raw was compacted or rolled back.
            upsertTextUnit(userId, "raw_memory", content, "episodic", content,
                bytes(memory.get("embedding")), null, "", "", "long_term_memory", id,
                null, "legacy_long_term_memory", 0);
            String turnId = rowId("SELECT turn_id FROM curator_turns WHERE user_id=? AND session_id=? "
                + "AND user_message=? ORDER BY sequence DESC LIMIT 1", userId,
                text(memory.get("session_id")), content);
            if (!turnId.isBlank()) {
                UnitState unit = findByCanonicalKey(userId, "raw_memory|" + normalize(content));
                if (unit != null) addSource(unit.id(), "long_term_memory", id, turnId, content);
            }
        }
    }


    /** Upserts one normalized fact and binds its auditable source evidence. */
    public void syncFact(String userId, long factId, byte[] embedding, long batchSequence) {
        syncFact(userId, factId, "", embedding, batchSequence);
    }

    public void syncFact(String userId, long factId, String proposedText, byte[] embedding, long batchSequence) {
        if (userId == null || userId.isBlank() || factId <= 0) return;
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT id,predicate,value_text,scope,assertion,status,valid_from,valid_to,normalized_start,normalized_end,"
                + "source_turn_id,raw_text,supersedes_id FROM memory_fact WHERE user_id=? AND id=?",
            userId, factId);
        if (rows.isEmpty()) return;
        Map<String, Object> fact = rows.get(0);
        String predicate = text(fact.get("predicate"));
        String value = text(fact.get("value_text"));
        String scope = text(fact.get("scope"));
        String assertion = text(fact.get("assertion"));
        String canonicalKey = "fact|" + normalize(predicate) + "|" + normalize(scope) + "|"
            + normalize(assertion) + "|" + normalize(value) + "|"
            + normalize(text(fact.get("normalized_start"))) + "|" + normalize(text(fact.get("normalized_end")));
        String factStatus = text(fact.get("status"));
        String content = factContent(predicate, value, "",
            "superseded".equals(factStatus) ? "historical" : scope, assertion);
        String status = Set.of("rolled_back", "merged_duplicate").contains(factStatus) ? factStatus : "superseded".equals(factStatus) ? "historical"
            : "inactive".equals(factStatus) && !"negated".equals(assertion) ? "inactive" : "active";
        int searchable = Set.of("inactive", "rolled_back", "merged_duplicate").contains(status) ? 0 : 1;
        long predecessorFactId = number(fact.get("supersedes_id"));
        String predecessorUnitId = predecessorFactId <= 0 ? "" : unitIdForFact(userId, predecessorFactId);
        int tokenCount = tokenCount(content);
        UnitState existing = findByFactId(userId, factId);
        String unitId = existing == null ? stableId("fact", userId, Long.toString(factId)) : existing.id();
        snapshotUnit(userId, unitId, batchSequence);
        jdbc.update("INSERT INTO memory_retrieval_unit(id,user_id,canonical_key,unit_type,predicate,scope,status,searchable,"
                + "content,embedding,token_count,valid_from,valid_to,fact_id,supersedes_unit_id,compaction_version) "
                + "VALUES(?, ?, ?, 'fact', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1) "
                + "ON CONFLICT(id) DO UPDATE SET predicate=excluded.predicate,scope=excluded.scope,status=excluded.status,"
                + "searchable=excluded.searchable,content=excluded.content,embedding=COALESCE(excluded.embedding,memory_retrieval_unit.embedding),"
                + "token_count=excluded.token_count,valid_from=excluded.valid_from,valid_to=excluded.valid_to,fact_id=excluded.fact_id,"
                + "supersedes_unit_id=COALESCE(excluded.supersedes_unit_id,memory_retrieval_unit.supersedes_unit_id),"
                + "compaction_version=memory_retrieval_unit.compaction_version+1,updated_at=CURRENT_TIMESTAMP",
            unitId, userId, canonicalKey, predicate, scope, status, searchable, content, embedding,
            tokenCount, emptyToNull(text(fact.get("valid_from"))), emptyToNull(text(fact.get("valid_to"))),
            factId, predecessorUnitId.isBlank() ? null : predecessorUnitId);
        addSource(unitId, "memory_fact", Long.toString(factId), text(fact.get("source_turn_id")),
            text(fact.get("raw_text")), batchSequence);
        addSource(unitId, "curator_turn", text(fact.get("source_turn_id")), text(fact.get("source_turn_id")),
            text(fact.get("raw_text")), batchSequence);
        String action = !predecessorUnitId.isBlank() ? "SUPERSEDE"
            : existing == null ? "KEEP" : "MERGE";
        if ("inactive".equals(status)) action = "RETIRE";
        int before = existing == null ? 0 : existing.tokenCount();
        log(userId, batchSequence, action, unitId, Long.toString(factId), before,
            searchable == 1 ? tokenCount : 0, "fact_status=" + factStatus);
    }

    /** Reconciles projected statuses after fact supersession or negation. */
    public void reconcileFactStatuses(String userId, long batchSequence) {
        if (userId == null || userId.isBlank()) return;
        List<Map<String, Object>> changed = jdbc.queryForList(
            "SELECT u.id,u.fact_id,u.status,u.searchable,u.token_count,mf.assertion,mf.valid_to,mf.status AS fact_status "
                + "FROM memory_retrieval_unit u JOIN memory_fact mf ON mf.id=u.fact_id "
                + "WHERE u.user_id=? AND u.unit_type='fact'",
            userId);
        for (Map<String, Object> row : changed) {
            String factStatus = text(row.get("fact_status"));
            String next = Set.of("rolled_back", "merged_duplicate").contains(factStatus) ? factStatus : "superseded".equals(factStatus) ? "historical"
                : "inactive".equals(factStatus) && !"negated".equals(text(row.get("assertion"))) ? "inactive" : "active";
            int searchable = Set.of("inactive", "rolled_back", "merged_duplicate").contains(next) ? 0 : 1;
            String previous = text(row.get("status"));
            if (next.equals(previous) && searchable == (int) number(row.get("searchable"))) continue;
            if ("historical".equals(next)) {
                syncFact(userId, number(row.get("fact_id")), null, batchSequence);
                continue;
            }
            snapshotUnit(userId, text(row.get("id")), batchSequence);
            jdbc.update("UPDATE memory_retrieval_unit SET status=?,searchable=?,valid_to=?,"
                + "compaction_version=compaction_version+1,updated_at=CURRENT_TIMESTAMP "
                + "WHERE user_id=? AND id=?", next, searchable, row.get("valid_to"), userId, text(row.get("id")));
            log(userId, batchSequence, "superseded".equals(next) ? "SUPERSEDE" : "RETIRE",
                text(row.get("id")), Long.toString(number(row.get("fact_id"))),
                (int) number(row.get("token_count")), searchable == 1 ? (int) number(row.get("token_count")) : 0,
                "fact_status=" + factStatus);
        }
    }

    public void syncInsight(String userId, String content, String context, byte[] embedding,
                            List<CuratorTurnStore.CompletedTurn> sources, long batchSequence) {
        String sourceId = rowId("SELECT id FROM user_insight WHERE user_id=? AND insight=?", userId, content);
        if (sourceId.isBlank()) return;
        upsertTextUnit(userId, "insight", content, "stable", content, embedding, null, "", "",
            "user_insight", sourceId, context, "insight", batchSequence);
        syncCitationSources(userId, "insight", content, context, sources, batchSequence);
    }

    public void syncGrowth(String userId, String category, String content, String context, byte[] embedding,
                          List<CuratorTurnStore.CompletedTurn> sources, long batchSequence) {
        String sourceId = rowId("SELECT id FROM llm_growth WHERE user_id=? AND category=? AND insight=?",
            userId, category, content);
        if (sourceId.isBlank()) return;
        String key = category + ":" + content;
        retirePreviousGrowthUnit(userId, sourceId, "growth|" + normalize(key), batchSequence);
        upsertTextUnit(userId, "growth", key, "stable", "[" + category + "] " + content, embedding, null,
            "", "", "llm_growth", sourceId, context, "growth", batchSequence);
        syncCitationSources(userId, "growth", key, context, sources, batchSequence);
    }

    /** Reindexes a growth row that may have been updated by the reflection pipeline. */
    @Transactional
    public void syncGrowthRecord(String userId, String sourceType, String sourceId, long batchSequence) {
        if (userId == null || userId.isBlank() || sourceType == null || sourceType.isBlank()
                || sourceId == null || sourceId.isBlank()) return;
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT category,insight,context,embedding FROM llm_growth WHERE user_id=? "
                + "AND category='memory_reflection' AND source_type=? AND source_id=? LIMIT 1",
            userId, sourceType, sourceId);
        if (rows.isEmpty()) return;
        Map<String, Object> row = rows.get(0);
        String category = text(row.get("category"));
        String content = text(row.get("insight"));
        if (MemoryContentSafety.looksSensitive(content) || MemoryContentSafety.looksSensitive(text(row.get("context")))) return;
        syncGrowth(userId, category, content, text(row.get("context")), bytes(row.get("embedding")), null, batchSequence);
    }

    private void retirePreviousGrowthUnit(String userId, String sourceId, String nextCanonicalKey,
                                         long batchSequence) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT u.id,u.token_count,u.canonical_key FROM memory_retrieval_unit u "
                + "JOIN memory_retrieval_source s ON s.unit_id=u.id "
                + "WHERE u.user_id=? AND s.source_type='llm_growth' AND s.source_id=? "
                + "AND u.searchable=1 AND u.status='active'", userId, sourceId);
        for (Map<String, Object> row : rows) {
            if (nextCanonicalKey.equals(text(row.get("canonical_key")))) continue;
            int tokens = (int) number(row.get("token_count"));
            snapshotUnit(userId, text(row.get("id")), batchSequence);
            jdbc.update("UPDATE memory_retrieval_unit SET status='inactive',searchable=0,"
                    + "compaction_version=compaction_version+1,updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND id=?",
                userId, text(row.get("id")));
            log(userId, batchSequence, "SUPERSEDE", text(row.get("id")), sourceId, tokens, 0,
                "growth_record_updated");
        }
    }

    public void recordFactEvidence(String userId, long factId, CuratorTurnStore.CompletedTurn source,
                                   String evidence, long batchSequence) {
        recordFactEvidence(userId, factId, source, evidence, "", batchSequence);
    }

    public void recordFactEvidence(String userId, long factId, CuratorTurnStore.CompletedTurn source,
                                   String evidence, String surfaceValue, long batchSequence) {
        if (source == null || source.turnId() == null || source.turnId().isBlank()) return;
        String unitId = unitIdForFact(userId, factId);
        if (unitId.isBlank()) return;
        addSource(unitId, "curator_turn", source.turnId(), source.turnId(), evidence, batchSequence);
        int start=surfaceValue==null||surfaceValue.isBlank()?-1:evidence.indexOf(surfaceValue);
        jdbc.update("UPDATE memory_retrieval_source SET surface_value=?,value_start=?,value_end=? WHERE unit_id=? AND source_type='curator_turn' AND source_id=?",
            surfaceValue == null ? "" : surfaceValue,start,start<0?-1:start+surfaceValue.length(),unitId,source.turnId());
        jdbc.update("UPDATE memory_fact SET evidence_count=(SELECT COUNT(DISTINCT source_turn_id) "
            + "FROM memory_retrieval_source WHERE unit_id=? AND source_type='curator_turn') WHERE user_id=? AND id=?",
            unitId, userId, factId);
        jdbc.update("UPDATE memory_fact SET observed_at=CASE WHEN observed_at IS NOT NULL AND ?>observed_at THEN observed_at "
            + "ELSE ? END,valid_from=CASE WHEN scope IN ('current','stable') AND COALESCE(normalized_start,'')='' AND valid_from IS NOT NULL AND ?<valid_from "
            + "THEN ? ELSE valid_from END WHERE user_id=? AND id=?",
            source.occurredAt(), source.occurredAt(), source.occurredAt().substring(0, 10), source.occurredAt().substring(0, 10), userId, factId);
    }

    public record CompactionDecision(String rawUnitId, String action, List<String> targetUnitIds, String reason) {
        public CompactionDecision { targetUnitIds = List.copyOf(targetUnitIds); }
    }
    public record CompactionPlan(String userId, long batchSequence, List<CompactionDecision> decisions) {
        public CompactionPlan { decisions = List.copyOf(decisions); }
    }
    private record CoveredFact(String unitId, MemoryEvidenceCoverage.Evidence evidence) {}

    /** Construct the complete plan without changing raw visibility. LLM IDs are not inputs. */
    public CompactionPlan planCompaction(String userId, long batchSequence) {
        List<CompactionDecision> decisions = new ArrayList<>();
        List<String> rawIds = jdbc.query("SELECT id FROM memory_retrieval_unit WHERE user_id=? "
            + "AND unit_type='raw_memory' AND status='active' AND searchable=1 ORDER BY id",
            (rs, row) -> rs.getString(1), userId);
        for (String rawId : rawIds) {
            List<Map<String, Object>> sources = rawSources(userId, rawId);
            if (sources.isEmpty() || sources.stream().anyMatch(source ->
                    text(source.get("source_turn_id")).isBlank() || number(source.get("turn_exists")) != 1)) {
                decisions.add(new CompactionDecision(rawId, "KEEP", List.of(), "provenance_pending"));
                continue;
            }
            Set<String> targets = new LinkedHashSet<>();
            boolean complete = true;
            String residual="";
            for (Map<String, Object> source : sources) {
                List<CoveredFact> facts = coveredFacts(userId, text(source.get("source_turn_id")));
                if (!"user".equals(text(source.get("role"))) || number(source.get("searchable")) != 1
                        || !normalize(text(source.get("content"))).equals(normalize(text(source.get("unit_content"))))) {
                    complete = false;
                    break;
                }
                facts.stream().filter(fact -> MemoryEvidenceCoverage.supports(fact.evidence().text(), fact.evidence()))
                    .map(CoveredFact::unitId).forEach(targets::add);
                if(!MemoryEvidenceCoverage.fullyCovered(text(source.get("content")),facts.stream().map(CoveredFact::evidence).toList())) {
                    complete=false;
                    if(sources.size()==1)residual=MemorySourceCoverage.residual(text(source.get("content")),facts.stream().map(CoveredFact::evidence).toList());
                }
            }
            boolean partial=!complete&&!residual.isBlank()&&!targets.isEmpty();
            decisions.add(new CompactionDecision(rawId, complete && !targets.isEmpty() ? "MERGE" : partial?"RESIDUAL":"KEEP",
                complete||partial ? new ArrayList<>(targets) : List.of(), complete ? "complete_fact_coverage" : partial?json(Map.of("residual",residual)):"uncovered_source_clause"));
        }
        return new CompactionPlan(userId, batchSequence, decisions);
    }

    private List<Map<String, Object>> rawSources(String userId, String rawId) {
        return jdbc.queryForList("SELECT s.source_id,s.source_turn_id,m.content,m.role,m.searchable,u.content AS unit_content,"
            + "EXISTS(SELECT 1 FROM curator_turns ct WHERE ct.user_id=? AND ct.turn_id=s.source_turn_id) AS turn_exists "
            + "FROM memory_retrieval_unit u JOIN memory_retrieval_source s ON s.unit_id=u.id AND s.source_type='long_term_memory' "
            + "JOIN long_term_memory m ON CAST(m.id AS TEXT)=s.source_id AND m.user_id=u.user_id "
            + "WHERE u.user_id=? AND u.id=? AND u.unit_type='raw_memory' AND u.searchable=1 ORDER BY m.id", userId, userId, rawId);
    }

    private List<CoveredFact> coveredFacts(String userId, String turnId) {
        return jdbc.query("SELECT u.id,f.predicate,f.value_text,f.scope,f.assertion,s.evidence_text,s.surface_value "
            + "FROM memory_retrieval_unit u JOIN memory_fact f ON f.id=u.fact_id AND f.user_id=u.user_id "
            + "JOIN memory_retrieval_source s ON s.unit_id=u.id AND s.source_type='curator_turn' "
            + "WHERE u.user_id=? AND u.unit_type='fact' AND u.searchable=1 AND u.status IN ('active','historical') "
            + "AND s.source_turn_id=?", (rs, row) -> new CoveredFact(rs.getString(1), new MemoryEvidenceCoverage.Evidence(
                rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7))), userId, turnId);
    }

    /** Recheck all coverage before applying a plan inside the enclosing commit transaction. */
    public void applyCompactionPlan(CompactionPlan plan) {
        CompactionPlan actual = planCompaction(plan.userId(), plan.batchSequence());
        if (!actual.equals(plan)) throw new IllegalStateException("压缩计划的来源或事实状态已变化");
        for (CompactionDecision decision : plan.decisions()) {
            jdbc.update("INSERT OR REPLACE INTO memory_compaction_plan(user_id,batch_sequence,raw_unit_id,resolved_action,"
                + "target_unit_ids,reason,applied_action) VALUES(?,?,?,?,?,?,?)", plan.userId(), plan.batchSequence(),
                decision.rawUnitId(), decision.action(), json(decision.targetUnitIds()), decision.reason(), "PENDING");
            if (Set.of("MERGE","RESIDUAL").contains(decision.action())) {
                List<Map<String, Object>> sources = rawSources(plan.userId(), decision.rawUnitId());
                snapshotUnit(plan.userId(), decision.rawUnitId(), plan.batchSequence());
                int before = tokenCount(text(sources.get(0).get("unit_content")));
                String residual="";
                if("RESIDUAL".equals(decision.action())) {
                    try {residual=text(SNAPSHOT_MAPPER.readTree(decision.reason()).path("residual").asText());}
                    catch(Exception error){throw new IllegalStateException("剩余语料计划损坏",error);}
                    Map<String,Object> source=sources.get(0);
                    upsertTextUnit(plan.userId(),"residual_memory",decision.rawUnitId()+"|"+residual,"episodic",residual,null,null,"","",
                        "long_term_memory",text(source.get("source_id")),text(source.get("content")),"uncovered_sentences",plan.batchSequence());
                    UnitState remaining=findByCanonicalKey(plan.userId(),"residual_memory|"+normalize(decision.rawUnitId()+"|"+residual));
                    addSource(remaining.id(),"long_term_memory",text(source.get("source_id")),text(source.get("source_turn_id")),text(source.get("content")),plan.batchSequence());
                }
                for (Map<String, Object> source : sources) {
                    String sourceId = text(source.get("source_id"));
                    snapshotLongTermMemory(plan.userId(), sourceId, plan.batchSequence());
                    if (jdbc.update("UPDATE long_term_memory SET searchable=0 WHERE user_id=? AND id=? AND searchable=1",
                            plan.userId(), sourceId) != 1) throw new IllegalStateException("压缩来源未能原子退出检索");
                    for (String target : decision.targetUnitIds()) {
                        boolean supportsTarget = coveredFacts(plan.userId(), text(source.get("source_turn_id"))).stream()
                            .anyMatch(fact -> target.equals(fact.unitId()) && MemoryEvidenceCoverage.supports(fact.evidence().text(), fact.evidence()));
                        if (!supportsTarget) continue;
                        addSource(target, "long_term_memory", sourceId, text(source.get("source_turn_id")),
                            text(source.get("content")), plan.batchSequence());
                    }
                }
                if (jdbc.update("UPDATE memory_retrieval_unit SET searchable=0,status='compacted',"
                    + "compaction_version=compaction_version+1,updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND id=? AND searchable=1",
                    plan.userId(), decision.rawUnitId()) != 1) throw new IllegalStateException("压缩原始单元状态已变化");
                log(plan.userId(), plan.batchSequence(), decision.action(), decision.rawUnitId(),
                    String.join(",", decision.targetUnitIds()), before, tokenCount(residual), decision.reason());
            }
            jdbc.update("UPDATE memory_compaction_plan SET applied_action=? WHERE user_id=? AND batch_sequence=? AND raw_unit_id=?",
                decision.action(), plan.userId(), plan.batchSequence(), decision.rawUnitId());
        }
    }

    public void recordActionResolution(String userId, long sequence, String unitId, String proposed, String resolved,
                                      List<String> proposedIds, List<String> resolvedIds) {
        log(userId, sequence, "RESOLVE", unitId, String.join(",", resolvedIds), 0, 0,
            json(Map.of("proposed_action", proposed, "resolved_action", resolved, "applied_action", resolved,
                "proposed_replacements", proposedIds, "resolved_replacements", resolvedIds)));
    }

    private static String json(Object value) {
        try { return SNAPSHOT_MAPPER.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException("压缩审计序列化失败", error); }
    }

    /** Retrieves one deduplicated, token-bounded set of curator-maintained units. */
    public String getRetrievalContext(String userId, String query, float[] embedding) {
        List<RetrievalUnit> units = searchUnits(userId, query, embedding, 40, DEFAULT_TOKEN_BUDGET);
        StringBuilder context = new StringBuilder();
        for (RetrievalUnit unit : units) {
            if (context.isEmpty()) context.append("## 相关记忆\n");
            context.append("- [").append(scopeLabel(unit.scope(), unit.status())).append("] ")
                .append(unit.content());
            if (!text(unit.validFrom()).isBlank()) context.append("（自 ").append(unit.validFrom()).append(" 起");
            if (!text(unit.validTo()).isBlank()) context.append("，至 ").append(unit.validTo()).append("）");
            else if (!text(unit.validFrom()).isBlank()) context.append("）");
            context.append("\n");
        }
        return context.toString().trim();
    }

    /** Returns production-ranked active units, with history and plans admitted only for matching questions. */
    public List<RetrievalUnit> searchUnits(String userId, String query, float[] embedding,
                                           int limit, int tokenBudget) {
        ensureLegacyIndexed(userId);
        if (userId == null || userId.isBlank() || query == null || query.isBlank() || limit <= 0) return List.of();
        boolean historical = containsAny(query, "以前", "之前", "曾经", "过去", "原来", "历史", "当时", "那时", "老家",
            "搬家前", "搬迁前", "原先", "曾任", "曾住", "旧址", "曾想", "打算过");
        boolean planned = containsAny(query, "计划", "打算", "准备", "想要", "将来", "以后", "未来",
            "安排", "考虑", "曾想", "预期", "会不会", "打算过", "下一步", "届时", "尚未", "取消", "决定");
        boolean currentState = containsAny(query, "目前", "当前", "现在", "现居", "现职", "现任", "最新",
            "最近确认", "仍住", "仍然住", "现在住", "住在哪", "现在哪");
        Map<String, UnitCandidate> candidates = new LinkedHashMap<>();
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        if (embedding != null && vectorSearch != null) {
            List<VectorSearchService.VectorMatch> matches = vectorSearch.searchMemoryUnits(
                userId, embedding, 40, historical, planned);
            for (int i = 0; i < matches.size(); i++) {
                VectorSearchService.VectorMatch match = matches.get(i);
                if (!Double.isFinite(match.distance()) || match.distance() > MAX_VECTOR_DISTANCE) continue;
                UnitCandidate unit = loadCandidate(userId, match.id());
                if (unit != null) {
                    candidates.putIfAbsent(unit.id(), unit);
                    rrfScores.merge(unit.id(), 1.0 / (60.0 + i + 1), Double::sum);
                }
            }
        }
        List<UnitCandidate> keywordCandidates = jdbc.query(
            "SELECT id,canonical_key,unit_type,predicate,scope,status,content,token_count,valid_from,valid_to "
                + "FROM memory_retrieval_unit WHERE user_id=? AND searchable=1 "
                + "AND (status='active' OR (?=1 AND status='historical')) AND (?=1 OR scope<>'planned') "
                + "AND (?=1 OR scope<>'historical') "
                + "ORDER BY CASE scope WHEN 'current' THEN 0 WHEN 'stable' THEN 1 WHEN 'episodic' THEN 2 ELSE 3 END,updated_at DESC LIMIT 300",
            (rs, row) -> new UnitCandidate(rs.getString("id"), rs.getString("canonical_key"),
                rs.getString("unit_type"), rs.getString("predicate"), rs.getString("scope"), rs.getString("status"),
                rs.getString("content"), rs.getInt("token_count"), rs.getString("valid_from"), rs.getString("valid_to"), 0),
            userId, historical ? 1 : 0, planned ? 1 : 0, historical ? 1 : 0);
        List<UnitCandidate> lexicalMatches = keywordCandidates.stream()
            .map(unit -> unit.withScore(matchScore(unit.content(), query)))
            .filter(unit -> unit.score() >= 0.25)
            .sorted(Comparator.comparingDouble(UnitCandidate::score).reversed()
                .thenComparing(UnitCandidate::canonicalKey))
            .limit(40)
            .toList();
        for (int i = 0; i < lexicalMatches.size(); i++) {
            UnitCandidate unit = lexicalMatches.get(i);
            candidates.putIfAbsent(unit.id(), unit);
            rrfScores.merge(unit.id(), 1.0 / (60.0 + i + 1), Double::sum);
        }
        Map<String, UnitCandidate> byMeaning = new LinkedHashMap<>();
        for (UnitCandidate candidate : candidates.values()) {
            String key = semanticClusterKey(userId, candidate);
            UnitCandidate previous = byMeaning.get(key);
            if (previous == null || unitPriority(candidate.unitType()) > unitPriority(previous.unitType())) {
                byMeaning.put(key, candidate);
            }
        }
        List<UnitCandidate> ranked = byMeaning.values().stream()
            .map(unit -> unit.withScore(rrfScores.getOrDefault(unit.id(), 0.0)
                + intentBoost(unit, currentState, historical, planned)))
            .sorted(Comparator.comparingDouble(UnitCandidate::score).reversed()
                .thenComparing(UnitCandidate::canonicalKey))
            .toList();
        List<RetrievalUnit> result = new ArrayList<>();
        int usedTokens = 0;
        Set<String> seen = new LinkedHashSet<>();
        for (UnitCandidate unit : ranked) {
            if (!seen.add(unit.canonicalKey())) continue;
            int cost = Math.max(unit.tokenCount(), tokenCount(unit.content()));
            if (tokenBudget > 0 && usedTokens + cost > tokenBudget) continue;
            result.add(toRetrievalUnit(unit));
            usedTokens += cost;
            if (result.size() >= limit || (tokenBudget > 0 && usedTokens >= tokenBudget)) break;
        }
        for (RetrievalUnit unit : result) {
            if ("raw_memory".equals(unit.unitType())) touchRawMemory(userId, unit.id());
        }
        return result;
    }

    private static int unitPriority(String type) {
        return switch (type) { case "fact" -> 4; case "profile" -> 3; case "insight", "growth" -> 2; default -> 1; };
    }

    private String semanticClusterKey(String userId, UnitCandidate unit) {
        RetrievalUnit detailed = toRetrievalUnit(unit);
        if ("fact".equals(unit.unitType())) return factCluster(detailed);
        List<CoveredFact> evidence = detailed.sourceTurnIds().stream().flatMap(turn -> coveredFacts(userId, turn).stream()).toList();
        List<CoveredFact> supported = evidence.stream().filter(fact -> MemoryEvidenceCoverage.supports(unit.content(), fact.evidence())).toList();
        Set<String> keys = new LinkedHashSet<>();
        for (CoveredFact fact : supported) {
            UnitCandidate target = loadCandidate(userId, fact.unitId());
            if (target != null) keys.add(factCluster(toRetrievalUnit(target)));
        }
        if (keys.size() == 1 && MemoryEvidenceCoverage.fullyCovered(unit.content(), supported.stream().map(CoveredFact::evidence).toList())) {
            return keys.iterator().next();
        }
        return "text|" + normalize(unit.content());
    }

    private String factCluster(RetrievalUnit unit) {
        return "meaning|" + unit.predicate() + "|" + normalize(unit.value()) + "|" + unit.scope() + "|"
            + (Set.of("observed", "confirmed").contains(unit.assertion()) ? "affirmative" : unit.assertion())
            + ("historical".equals(unit.status()) || Set.of("episodic", "historical", "planned").contains(unit.scope())
                ? "|" + text(unit.validFrom()) + "|" + text(unit.validTo()) : "");
    }

    /** Merge relevant fallback candidates even when the curator already has a partial answer. */
    public List<RetrievalUnit> searchWithFallback(String userId, String query, float[] embedding,
                                                  int limit, int tokenBudget, SqliteMemoryService rawStore) {
        List<RetrievalUnit> curated = searchUnits(userId, query, embedding, Math.max(40, limit), 0);
        Map<String, RetrievalUnit> combined = new LinkedHashMap<>();
        for (RetrievalUnit unit : curated) combined.put(unit.id(), unit);
        if (rawStore != null && embedding != null) {
            for (SqliteMemoryService.MemoryResult raw : rawStore.search(userId, query, embedding, 40)) {
                if (raw.distance() > MAX_VECTOR_DISTANCE && matchScore(raw.content(), query) < 0.25) continue;
                List<String> mappedIds = jdbc.query("SELECT u.id FROM memory_retrieval_unit u JOIN memory_retrieval_source s "
                    + "ON s.unit_id=u.id WHERE u.user_id=? AND s.source_type='long_term_memory' AND s.source_id=? "
                    + "AND u.status IN ('active','historical','compacted','inactive')",
                    (rs, row) -> rs.getString(1), userId, raw.id());
                // Mapped sources obey the production visibility and intent filters above.
                if (!mappedIds.isEmpty()) continue;
                List<String> turnIds = jdbc.query("SELECT turn_id FROM curator_turns WHERE user_id=? AND user_message=?",
                    (rs, row) -> rs.getString(1), userId, raw.content());
                RetrievalUnit unit = new RetrievalUnit("fallback-" + raw.id(), "text|" + normalize(raw.content()),
                    "raw_memory", "", "", "episodic", "active", "", raw.content(), tokenCount(raw.content()),
                    "", "", "", "", turnIds, 1.0 / 61.0);
                combined.putIfAbsent(unit.id(), unit);
            }
        }
        Set<String> seen = new LinkedHashSet<>();
        List<RetrievalUnit> selected = new ArrayList<>();
        int tokens = 0;
        for (RetrievalUnit unit : combined.values().stream().sorted(Comparator.comparingDouble(RetrievalUnit::score).reversed()).toList()) {
            String key = unit.id().startsWith("fallback-") ? unit.canonicalKey()
                : semanticClusterKey(userId, loadCandidate(userId, unit.id()));
            if (seen.contains(key) || tokenBudget > 0 && tokens + unit.tokenCount() > tokenBudget) continue;
            selected.add(unit); seen.add(key); tokens += unit.tokenCount();
            if (selected.size() >= limit) break;
        }
        return selected;
    }

    public String getRetrievalContext(String userId, String query, float[] embedding, SqliteMemoryService rawStore) {
        StringBuilder out = new StringBuilder();
        for (RetrievalUnit unit : searchWithFallback(userId, query, embedding, 40, DEFAULT_TOKEN_BUDGET, rawStore)) {
            if (out.isEmpty()) out.append("## 相关记忆\n");
            out.append("- [").append(scopeLabel(unit.scope(), unit.status())).append("] ").append(unit.content()).append('\n');
        }
        return out.toString().trim();
    }

    private void touchRawMemory(String userId, String unitId) {
        jdbc.update("UPDATE long_term_memory SET access_count=COALESCE(access_count,0)+1,"
                + "last_accessed=CURRENT_TIMESTAMP WHERE user_id=? AND searchable=1 AND id IN ("
                + "SELECT CAST(source_id AS INTEGER) FROM memory_retrieval_source "
                + "WHERE unit_id=? AND source_type='long_term_memory')", userId, unitId);
    }

    private RetrievalUnit toRetrievalUnit(UnitCandidate unit) {
        String value = "";
        String assertion = "";
        List<Map<String, Object>> facts = jdbc.queryForList(
            "SELECT mf.value_text,mf.assertion,mf.normalized_start,mf.time_status FROM memory_retrieval_unit u "
                + "LEFT JOIN memory_fact mf ON mf.id=u.fact_id WHERE u.id=?", unit.id());
        if (!facts.isEmpty()) {
            value = text(facts.get(0).get("value_text"));
            assertion = text(facts.get(0).get("assertion"));
        }
        String normalizedStart = facts.isEmpty() ? "" : text(facts.get(0).get("normalized_start"));
        String timeStatus = facts.isEmpty() ? "" : text(facts.get(0).get("time_status"));
        List<String> sourceTurns = jdbc.query(
            "SELECT DISTINCT source_turn_id FROM memory_retrieval_source WHERE unit_id=? "
                + "AND source_turn_id IS NOT NULL AND source_turn_id<>'' ORDER BY source_turn_id",
            (rs, row) -> rs.getString(1), unit.id());
        return new RetrievalUnit(unit.id(), unit.canonicalKey(), unit.unitType(), unit.predicate(), value,
            unit.scope(), unit.status(), assertion, unit.content(), Math.max(unit.tokenCount(), tokenCount(unit.content())),
            unit.validFrom(), unit.validTo(), normalizedStart, timeStatus, sourceTurns, unit.score());
    }

    /** Compact candidate list supplied to the curator for duplicate and version decisions. */
    public String curatorContext(String userId) {
        ensureLegacyIndexed(userId);
        if (userId == null || userId.isBlank()) return "";
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT id,unit_type,predicate,scope,content FROM memory_retrieval_unit "
                + "WHERE user_id=? AND status='active' AND searchable=1 "
                + "AND unit_type NOT IN ('raw_memory','knowledge_graph_entity','knowledge_graph_relation') "
                + "ORDER BY CASE unit_type WHEN 'fact' THEN 0 WHEN 'profile' THEN 1 "
                + "WHEN 'insight' THEN 2 ELSE 3 END,"
                + "CASE scope WHEN 'current' THEN 0 WHEN 'stable' THEN 1 ELSE 2 END,updated_at DESC LIMIT ?",
            userId, MAX_CURATOR_CONTEXT_UNITS);
        StringBuilder out = new StringBuilder();
        if (!rows.isEmpty()) out.append("【当前可检索的规范记忆候选】\n");
        for (Map<String, Object> row : rows) {
            out.append("- unit_id=").append(row.get("id"))
                .append(" | type=").append(row.get("unit_type"))
                .append(" | scope=").append(row.get("scope"));
            String predicate = text(row.get("predicate"));
            if (!predicate.isBlank()) out.append(" | predicate=").append(predicate);
            out.append(" | content=").append(truncate(text(row.get("content")), 180)).append("\n");
        }
        List<Map<String, Object>> rawCandidates = jdbc.queryForList(
            "SELECT u.id,u.content,MAX(m.importance) AS importance,MAX(m.created_at) AS created_at "
                + "FROM memory_retrieval_unit u JOIN memory_retrieval_source s ON s.unit_id=u.id "
                + "AND s.source_type='long_term_memory' AND s.source_turn_id IS NOT NULL AND s.source_turn_id<>'' "
                + "JOIN long_term_memory m ON CAST(m.id AS TEXT)=s.source_id AND m.user_id=u.user_id "
                + "WHERE u.user_id=? AND u.unit_type='raw_memory' AND u.status='active' AND u.searchable=1 "
                + "AND m.role='user' AND m.searchable=1 GROUP BY u.id,u.content "
                + "ORDER BY MAX(m.importance) DESC,MAX(m.created_at) DESC LIMIT 20",
            userId);
        if (!rawCandidates.isEmpty()) {
            if (!out.isEmpty()) out.append("\n");
            out.append("【可用于合并的有来源原始记忆候选】\n")
                .append("只合并完整表达同一条事实的候选；复合句、含其他独立信息或来源不清的内容应保留。\n");
            for (Map<String, Object> row : rawCandidates) {
                out.append("- unit_id=").append(row.get("id"))
                    .append(" | importance=").append(row.get("importance"))
                    .append(" | content=").append(truncate(text(row.get("content")), 180)).append("\n");
            }
        }
        indexRetirementCandidates(userId, out);
        return out.toString().trim();
    }

    public String unitIdForFact(String userId, long factId) {
        if (userId == null || userId.isBlank() || factId <= 0) return "";
        try {
            String id = jdbc.queryForObject("SELECT id FROM memory_retrieval_unit WHERE user_id=? AND fact_id=?",
                String.class, userId, factId);
            return id == null ? "" : id;
        } catch (Exception ignored) { return ""; }
    }

    public String factStatus(String userId, long factId) {
        if (userId == null || userId.isBlank() || factId <= 0) return "";
        return rowId("SELECT status FROM memory_fact WHERE user_id=? AND id=?", userId, factId);
    }

    public List<String> activeFactUnitIds(String userId, String predicate, String scope, String value) {
        if (userId == null || userId.isBlank()) return List.of();
        String valueFilter = value == null ? "" : " AND mf.value_text=?";
        String sql = "SELECT u.id FROM memory_retrieval_unit u JOIN memory_fact mf ON mf.id=u.fact_id "
            + "WHERE u.user_id=? AND u.unit_type='fact' AND u.status='active' AND u.searchable=1 "
            + "AND mf.status='active' AND mf.predicate=? AND mf.scope=?" + valueFilter + " ORDER BY u.id";
        return value == null
            ? jdbc.query(sql, (rs, row) -> rs.getString(1), userId, predicate, scope)
            : jdbc.query(sql, (rs, row) -> rs.getString(1), userId, predicate, scope, value);
    }

    public boolean factUnitHasStatus(String userId, String unitId, String status) {
        if (userId == null || userId.isBlank() || unitId == null || unitId.isBlank()) return false;
        try {
            Boolean valid = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM memory_retrieval_unit u "
                    + "JOIN memory_fact mf ON mf.id=u.fact_id WHERE u.user_id=? AND u.id=? "
                    + "AND u.unit_type='fact' AND mf.status=?)", Boolean.class, userId, unitId, status);
            return Boolean.TRUE.equals(valid);
        } catch (Exception ignored) { return false; }
    }

    public boolean isReplaceableUnit(String userId, String unitId) {
        if (userId == null || userId.isBlank() || unitId == null || unitId.isBlank()) return false;
        try {
            Boolean valid = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM memory_retrieval_unit u "
                    + "JOIN memory_fact mf ON mf.id=u.fact_id WHERE u.user_id=? AND u.id=? "
                    + "AND mf.status IN ('superseded','inactive'))",
                Boolean.class, userId, unitId);
            return Boolean.TRUE.equals(valid);
        } catch (Exception ignored) { return false; }
    }

    public boolean isMergeableFactUnit(String userId, String unitId, long factId) {
        if (userId == null || userId.isBlank() || unitId == null || unitId.isBlank() || factId <= 0) return false;
        try {
            Boolean valid = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM memory_retrieval_unit u "
                    + "JOIN memory_fact mf ON mf.id=u.fact_id WHERE u.user_id=? AND u.id=? AND u.fact_id=? "
                    + "AND u.unit_type='fact' AND u.status='active' AND u.searchable=1 "
                    + "AND mf.status IN ('active','proposed'))",
                Boolean.class, userId, unitId, factId);
            return Boolean.TRUE.equals(valid);
        } catch (Exception ignored) { return false; }
    }

    /** Checks that a proposed replacement is a source-traceable, single-fact raw unit. */
    public boolean isSafeRawMemoryMerge(String userId, String unitId, String predicate, String value,
                                        String scope, String assertion) {
        if (userId == null || userId.isBlank() || unitId == null || unitId.isBlank()) return false;
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT u.content,u.token_count,m.id AS memory_id,m.content AS source_content,m.role,m.searchable,"
                + "s.source_turn_id FROM memory_retrieval_unit u "
                + "JOIN memory_retrieval_source s ON s.unit_id=u.id AND s.source_type='long_term_memory' "
                + "JOIN long_term_memory m ON CAST(m.id AS TEXT)=s.source_id AND m.user_id=u.user_id "
                + "WHERE u.user_id=? AND u.id=? AND u.unit_type='raw_memory' AND u.status='active' "
                + "AND u.searchable=1 ORDER BY m.id", userId, unitId);
        if (rows.isEmpty()) return false;
        String content = text(rows.get(0).get("content"));
        if (!supportsSingleFact(content, predicate, value, scope, assertion)
                || MemoryContentSafety.looksSensitive(content)) return false;
        for (Map<String, Object> row : rows) {
            if (!"user".equals(text(row.get("role"))) || number(row.get("searchable")) != 1
                    || text(row.get("source_turn_id")).isBlank()
                    || !normalize(content).equals(normalize(text(row.get("source_content"))))) return false;
            Boolean sourceExists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM curator_turns WHERE user_id=? AND turn_id=?)",
                Boolean.class, userId, text(row.get("source_turn_id")));
            if (!Boolean.TRUE.equals(sourceExists)) return false;
        }
        List<String> otherValues = jdbc.query(
            "SELECT value_text FROM memory_fact WHERE user_id=? AND status IN ('active','proposed') AND value_text<>?",
            (rs, row) -> rs.getString(1), userId, text(value));
        for (String otherValue : otherValues) {
            String normalizedOther = normalize(otherValue);
            if (normalizedOther.length() >= 2 && normalize(content).contains(normalizedOther)) return false;
        }
        return true;
    }

    /** Moves a validated raw unit's original sources onto the canonical fact unit. */
    @Transactional
    public int mergeRawMemoryUnit(String userId, String rawUnitId, String factUnitId,
                                  String predicate, String value, String scope, String assertion,
                                  long batchSequence) {
        if (!isSafeRawMemoryMerge(userId, rawUnitId, predicate, value, scope, assertion)
                || !isActiveFactUnit(userId, factUnitId)) return 0;
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT s.source_id,s.source_turn_id,m.content,u.content AS unit_content FROM memory_retrieval_source s "
                + "JOIN memory_retrieval_unit u ON u.id=s.unit_id AND u.user_id=? "
                + "JOIN long_term_memory m ON CAST(m.id AS TEXT)=s.source_id AND m.user_id=? "
                + "WHERE s.unit_id=? AND s.source_type='long_term_memory' AND m.searchable=1 ORDER BY m.id",
            userId, userId, rawUnitId);
        if (rows.isEmpty()) return 0;
        for (Map<String, Object> row : rows) {
            String turnId = text(row.get("source_turn_id"));
            String content = text(row.get("content"));
            if (turnId.isBlank() || !normalize(content).equals(normalize(text(row.get("unit_content"))))) return 0;
        }
        snapshotUnit(userId, rawUnitId, batchSequence);
        snapshotUnit(userId, factUnitId, batchSequence);
        int before = 0;
        int retired = 0;
        for (Map<String, Object> row : rows) {
            String sourceId = text(row.get("source_id"));
            String turnId = text(row.get("source_turn_id"));
            String content = text(row.get("content"));
            snapshotLongTermMemory(userId, sourceId, batchSequence);
            int changed = jdbc.update("UPDATE long_term_memory SET searchable=0 WHERE user_id=? AND id=? AND searchable=1",
                userId, sourceId);
            if (changed != 1) throw new IllegalStateException("原始记忆来源状态在合并过程中发生变化");
            before += tokenCount(content);
            retired++;
            addSource(factUnitId, "long_term_memory", sourceId, turnId, content, batchSequence);
        }
        if (retired != rows.size()) throw new IllegalStateException("原始记忆来源未能全部合并");
        int changed = jdbc.update("UPDATE memory_retrieval_unit SET searchable=0,status='compacted',"
                + "compaction_version=compaction_version+1,updated_at=CURRENT_TIMESTAMP "
                + "WHERE user_id=? AND id=? AND unit_type='raw_memory' AND searchable=1",
            userId, rawUnitId);
        if (changed != 1) throw new IllegalStateException("原始记忆单元未能原子退出默认检索");
        log(userId, batchSequence, "MERGE", rawUnitId, factUnitId, before, 0, "curator_replaced_raw_fact");
        return before;
    }

    private boolean isActiveFactUnit(String userId, String unitId) {
        if (userId == null || userId.isBlank() || unitId == null || unitId.isBlank()) return false;
        try {
            Boolean valid = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM memory_retrieval_unit "
                    + "WHERE user_id=? AND id=? AND unit_type='fact' AND status='active' AND searchable=1)",
                Boolean.class, userId, unitId);
            return Boolean.TRUE.equals(valid);
        } catch (Exception ignored) { return false; }
    }

    /** Soft-retires only a low-importance, low-use raw user memory named in curator context. */
    @Transactional
    public boolean retireRawMemory(String userId, String unitId, String reason, long batchSequence) {
        if (userId == null || userId.isBlank() || unitId == null || unitId.isBlank()
                || !"non_durable_noise".equals(reason)) return false;
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT s.source_id,s.source_turn_id,m.content,m.importance,m.access_count,m.role,m.searchable "
                + "FROM memory_retrieval_unit u JOIN memory_retrieval_source s ON s.unit_id=u.id "
                + "JOIN long_term_memory m ON CAST(m.id AS TEXT)=s.source_id AND m.user_id=u.user_id "
                + "WHERE u.user_id=? AND u.id=? AND u.unit_type='raw_memory' AND u.status='active' "
                + "AND u.searchable=1 AND s.source_type='long_term_memory' AND m.searchable=1 "
                + "ORDER BY m.id", userId, unitId);
        if (rows.isEmpty()) return isAlreadyRetired(userId, unitId);
        String content = text(rows.get(0).get("content"));
        for (Map<String, Object> row : rows) {
            String sourceContent = text(row.get("content"));
            if (!"user".equals(text(row.get("role"))) || number(row.get("searchable")) != 1
                    || text(row.get("source_turn_id")).isBlank()
                    || !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM curator_turns WHERE user_id=? AND turn_id=?)",
                        Boolean.class, userId, text(row.get("source_turn_id"))))
                    || number(row.get("importance")) > 0.30 || number(row.get("access_count")) >= 3
                    || MemoryContentSafety.looksSensitive(sourceContent) || hasDurableSignal(sourceContent)
                    || !normalize(content).equals(normalize(sourceContent))) return false;
        }
        snapshotUnit(userId, unitId, batchSequence);
        int changed = 0;
        List<String> sourceIds = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String sourceId = text(row.get("source_id"));
            snapshotLongTermMemory(userId, sourceId, batchSequence);
            changed += jdbc.update("UPDATE long_term_memory SET searchable=0 WHERE user_id=? AND id=? AND searchable=1",
                userId, sourceId);
            sourceIds.add(sourceId);
        }
        if (changed == 0) return isAlreadyRetired(userId, unitId);
        int tokens = tokenCount(content);
        jdbc.update("UPDATE memory_retrieval_unit SET searchable=0,status='inactive',"
                + "compaction_version=compaction_version+1,updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND id=?",
            userId, unitId);
        log(userId, batchSequence, "RETIRE", unitId, String.join(",", sourceIds), tokens, 0, reason);
        return true;
    }

    private boolean isAlreadyRetired(String userId, String unitId) {
        try {
            Boolean retired = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM memory_retrieval_unit "
                    + "WHERE user_id=? AND id=? AND unit_type='raw_memory' "
                    + "AND status IN ('inactive','compacted') AND searchable=0)",
                Boolean.class, userId, unitId);
            return Boolean.TRUE.equals(retired);
        } catch (Exception ignored) { return false; }
    }

    private void indexRetirementCandidates(String userId, StringBuilder out) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT u.id,u.content,MAX(m.importance) AS importance,MAX(m.access_count) AS access_count,"
                + "MIN(m.created_at) AS created_at FROM memory_retrieval_unit u "
                + "JOIN memory_retrieval_source s ON s.unit_id=u.id "
                + "JOIN long_term_memory m ON CAST(m.id AS TEXT)=s.source_id AND m.user_id=u.user_id "
                + "WHERE u.user_id=? AND u.unit_type='raw_memory' AND u.status='active' AND u.searchable=1 "
                + "AND s.source_type='long_term_memory' AND m.role='user' AND m.searchable=1 "
                + "AND m.importance<=0.30 AND m.access_count<3 "
                + "AND NOT EXISTS (SELECT 1 FROM memory_retrieval_source s2 "
                + "JOIN long_term_memory m2 ON CAST(m2.id AS TEXT)=s2.source_id AND m2.user_id=u.user_id "
                + "WHERE s2.unit_id=u.id AND s2.source_type='long_term_memory' AND m2.searchable=1 "
                + "AND (m2.role<>'user' OR m2.importance>0.30 OR m2.access_count>=3)) "
                + "GROUP BY u.id,u.content ORDER BY MAX(m.importance) ASC,MIN(m.created_at) ASC LIMIT 30",
            userId);
        int included = 0;
        for (Map<String, Object> row : rows) {
            String content = text(row.get("content"));
            if (content.isBlank() || MemoryContentSafety.looksSensitive(content) || hasDurableSignal(content)) continue;
            if (included == 0) out.append("\n【可提议退出默认检索的低价值原始记忆候选】\n")
                .append("只能从以下候选中提议退役；每批最多退役 10 条，reason 固定为 non_durable_noise。\n");
            out.append("- unit_id=").append(row.get("id"))
                .append(" | importance=").append(row.get("importance"))
                .append(" | access_count=").append(row.get("access_count"))
                .append(" | content=").append(truncate(content, 180)).append("\n");
            included++;
        }
    }

    private static boolean hasDurableSignal(String content) {
        String value = normalize(content);
        return containsAny(value, "我叫", "我是", "我的职业", "我在做", "我住在", "我来自", "我的家人",
            "我喜欢", "我不喜欢", "我偏好", "我的习惯", "我希望你", "请记住", "长期", "一直以来",
            "我过敏", "我的生日", "我的目标", "我的计划", "我正在", "我有一个", "我养了");
    }

    private static boolean supportsSingleFact(String content, String predicate, String value,
                                              String scope, String assertion) {
        String normalized = normalize(content);
        String normalizedValue = normalize(value);
        if (normalized.isBlank() || normalizedValue.length() < 2 || !normalized.contains(normalizedValue)
                || tokenCount(content) > 80 || content.length() > 240
                || Set.of("negated", "uncertain").contains(assertion)) return false;
        String canonicalPredicate = predicate == null ? "" : predicate;
        if (hasIndependentFactCue(normalized, canonicalPredicate)
                || hasUnsupportedClause(normalized, normalizedValue, canonicalPredicate, scope)) return false;
        return switch (canonicalPredicate) {
            case "current_location" -> switch (scope == null ? "" : scope) {
                case "current" -> containsAny(normalized, "现居", "现在住", "目前住", "当前住", "现住", "住在", "居住在", "搬到", "currentcity", "currentaddress", "livesin", "residesin");
                case "historical" -> containsAny(normalized, "之前住", "以前住", "过去住", "过去的住处", "以前的住处",
                    "搬迁前", "搬家前", "曾经住", "旧址", "previouslylived", "formerlylived", "pastaddress",
                    "不要再把它当作现居地", "不再把它当作现居地", "不是现居地");
                case "planned" -> containsAny(normalized, "以后可能搬", "计划搬", "打算搬", "考虑搬", "将来搬", "可能搬去",
                    "可能去", "计划去", "打算去", "准备去", "备选去向", "迁居选项", "maymoveto", "planstomove");
                default -> false;
            };
            case "home_location" -> "stable".equals(scope)
                && containsAny(normalized, "老家", "长期住", "长期居住", "家在", "我的家", "homecity", "longtermhome", "homebase");
            case "occupation_current" -> containsAny(normalized, "工作", "职位", "岗位", "职业", "任职", "职业是", "job", "occupation", "position", "workas")
                && ("historical".equals(scope)
                    ? containsAny(normalized, "以前", "之前", "过去", "曾经", "旧职", "former", "previous")
                    : "current".equals(scope) && containsAny(normalized, "现在", "目前", "当前", "现职", "现任", "现在的", "current", "currently"));
            case "current_project" -> containsAny(normalized, "项目", "负责", "project")
                && ("historical".equals(scope)
                    ? containsAny(normalized, "以前", "之前", "过去", "旧项目", "former", "previous")
                    : "current".equals(scope) && containsAny(normalized, "现在", "目前", "当前", "仍由我", "正在", "current", "currently"));
            case "preference" -> containsAny(normalized, "偏好", "喜欢", "习惯", "希望", "prefer", "likes", "prefers");
            default -> false;
        };
    }

    private static boolean hasUnsupportedClause(String normalized, String normalizedValue,
                                                String predicate, String scope) {
        String marked = normalized.replace(normalizedValue, "factvalueplaceholder");
        String[] clauses = marked.split("[，,、；;。！？!?]+", -1);
        for (String clause : clauses) {
            String part = clause.trim();
            if (part.isBlank()) continue;
            if (containsAny(part, "更正近况", "补充确认", "再核对一次", "再次确认", "重新确认", "回顾旧项目")) continue;
            if (!hasPredicateCue(part, predicate, scope)) return true;
            if (part.contains("factvalueplaceholder")) continue;
            if (containsAny(part, "这里", "那里", "这儿", "那儿", "此地", "此处", "它", "this", "there", "it")) continue;
            return true;
        }
        return false;
    }

    private static boolean hasPredicateCue(String text, String predicate, String scope) {
        return switch (predicate) {
            case "current_location" -> switch (scope == null ? "" : scope) {
                case "current" -> containsAny(text, "现居", "现在住", "目前住", "当前住", "现住", "住在", "居住在", "搬到",
                    "currentcity", "currentaddress", "livesin", "residesin");
                case "historical" -> containsAny(text, "之前住", "以前住", "过去住", "过去的住处", "以前的住处", "搬迁前",
                    "搬家前", "曾经住", "旧址", "previouslylived", "formerlylived", "pastaddress", "现居地");
                case "planned" -> containsAny(text, "计划搬", "打算搬", "考虑搬", "将来搬", "可能搬", "可能去", "计划去",
                    "打算去", "准备去", "迁居", "maymoveto", "planstomove");
                default -> false;
            };
            case "home_location" -> containsAny(text, "老家", "长期住", "长期居住", "家在", "我的家", "homecity", "longtermhome", "homebase");
            case "occupation_current" -> containsAny(text, "工作", "职位", "岗位", "职业", "任职", "job", "occupation", "position", "workas");
            case "current_project" -> containsAny(text, "项目", "负责", "project");
            case "preference" -> containsAny(text, "偏好", "喜欢", "习惯", "希望", "prefer", "likes", "prefers");
            default -> false;
        };
    }

    private static boolean hasIndependentFactCue(String text, String predicate) {
        if (!Set.of("current_location", "home_location").contains(predicate)
                && containsAny(text, "住在", "现居", "住址", "搬到", "居住", "livesin", "residesin")) return true;
        if (!"occupation_current".equals(predicate)
                && containsAny(text, "职业", "职位", "岗位", "任职", "工作", "occupation", "job")) return true;
        if (!"current_project".equals(predicate)
                && containsAny(text, "项目", "负责", "project")) return true;
        if (!"preference".equals(predicate)
                && containsAny(text, "偏好", "喜欢", "习惯", "prefer", "likes")) return true;
        return containsAny(text, "家人", "妈妈", "爸爸", "父母", "妻子", "丈夫", "配偶", "朋友", "同事", "同学");
    }

    public static String factContent(String predicate, String value, String proposedText, String scope,
                              String assertion) {
        String cleanValue = text(value);
        String candidate = text(proposedText);
        String normalizedPredicate = text(predicate).toLowerCase(java.util.Locale.ROOT);
        String normalizedScope = text(scope).toLowerCase(java.util.Locale.ROOT);
        String normalizedAssertion = text(assertion).toLowerCase(java.util.Locale.ROOT);
        if ("negated".equals(normalizedAssertion)) {
            return ("historical".equals(normalizedScope) ? "用户曾否定或取消" : "用户已否定或取消") + switch (normalizedPredicate) {
                case "plan", "event" -> "原计划：";
                case "current_location" -> "居住状态：";
                case "preference" -> "原偏好：";
                default -> "原事实（" + normalizedPredicate + "）：";
            } + cleanValue;
        }
        if ("historical".equals(normalizedScope) && Set.of("plan", "event").contains(normalizedPredicate)) {
            return "用户曾" + (Set.of("possible", "uncertain").contains(normalizedAssertion) ? "考虑" : "计划") + "：" + cleanValue;
        }
        String requiredCue = switch (normalizedPredicate) {
            case "current_location" -> switch (normalizedScope) {
                case "historical" -> "过去居住城市";
                case "planned" -> "possible".equals(normalizedAssertion) ? "可能前往城市" : "计划前往城市";
                default -> "现居城市";
            };
            case "home_location" -> "长期住址";
            case "occupation_current" -> "historical".equals(normalizedScope) ? "曾任职位" : "当前职位";
            case "current_project" -> "historical".equals(normalizedScope) ? "曾负责项目" : "当前项目";
            default -> "";
        };
        Set<String> acceptedCues = switch (normalizedPredicate) {
            case "current_location" -> switch (normalizedScope) {
                case "historical" -> Set.of("过去居住", "曾经住在", "曾居住", "历史住址", "搬家前住在", "之前住在");
                case "planned" -> "possible".equals(normalizedAssertion)
                    ? Set.of("可能前往", "可能去", "考虑前往", "可能搬去", "尚未搬到")
                    : Set.of("计划前往", "准备前往", "打算去", "已安排搬去", "计划搬到");
                default -> Set.of("现居城市", "目前居住", "当前住在", "现在住在", "现住在");
            };
            case "home_location" -> Set.of("长期住址", "长期居住", "家在", "老家在");
            case "occupation_current" -> "historical".equals(normalizedScope)
                ? Set.of("曾任职位", "过去从事", "曾担任", "以前的职位")
                : Set.of("当前职位", "现任职位", "目前担任", "现在从事");
            case "current_project" -> "historical".equals(normalizedScope)
                ? Set.of("曾负责项目", "过去负责", "以前负责", "曾参与项目")
                : Set.of("当前项目", "目前负责", "仍在负责", "现在负责");
            default -> Set.of();
        };
        boolean safeCandidate = !candidate.isBlank() && candidate.length() <= 300
            && !MemoryContentSafety.looksSensitive(candidate)
            && normalize(candidate).contains(normalize(cleanValue));
        boolean hasScopeCue = acceptedCues.isEmpty() || acceptedCues.stream()
            .anyMatch(cue -> normalize(candidate).contains(normalize(cue)));
        safeCandidate &= MemoryContentSafety.polarityConsistent(candidate, cleanValue, normalizedAssertion, normalizedScope);
        if (safeCandidate && hasScopeCue) return candidate;
        if (!requiredCue.isBlank()) return "用户" + requiredCue + "：" + cleanValue;
        if ("plan".equals(normalizedPredicate) && "planned".equals(normalizedScope)) {
            return "possible".equals(normalizedAssertion) || "uncertain".equals(normalizedAssertion)
                ? "用户可能计划（尚未决定）：" + cleanValue : "用户已安排计划（尚未完成）：" + cleanValue;
        }
        String label = text(predicate);
        if (label.isBlank()) label = "记忆";
        return safeCandidate ? candidate : label + "：" + cleanValue;
    }

    private static double intentBoost(UnitCandidate unit, boolean currentState,
                                      boolean historical, boolean planned) {
        if (!"active".equals(unit.status())) {
            return historical && "historical".equals(unit.status()) ? 0.004 : 0.0;
        }
        if (planned && "planned".equals(unit.scope())) return 0.004;
        if (currentState && "current".equals(unit.scope())) return 0.004;
        if (currentState && "stable".equals(unit.scope())) return 0.002;
        return 0.0;
    }

    static int tokenCount(String value) { return MemoryTokenCounter.count(value); }

    static int legacyUnicodeTokenCount(String value) {
        if (value == null || value.isBlank()) return 0;
        int count = 0;
        int asciiChars = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) continue;
            if (Character.isLetterOrDigit(codePoint) && codePoint < 128) {
                asciiChars++;
                continue;
            }
            if (asciiChars > 0) {
                count += Math.max(1, (asciiChars + 3) / 4);
                asciiChars = 0;
            }
            count++;
        }
        if (asciiChars > 0) count += Math.max(1, (asciiChars + 3) / 4);
        return count;
    }

    private void syncCitationSources(String userId, String unitType, String canonicalContent, String evidence,
                                    List<CuratorTurnStore.CompletedTurn> sources, long batchSequence) {
        if (sources == null) return;
        String unitKey = unitType + "|" + normalize(canonicalContent);
        UnitState unit = findByCanonicalKey(userId, unitKey);
        if (unit == null) return;
        for (CuratorTurnStore.CompletedTurn turn : sources) {
            if (turn == null) continue;
            String cleanEvidence = text(evidence);
            if (cleanEvidence.isBlank() || !MemoryContentSafety.supportsExactEvidence(turn.userMessage(), cleanEvidence)) continue;
            addSource(unit.id(), "curator_turn", turn.turnId(), turn.turnId(), cleanEvidence, batchSequence);
        }
    }

    private void upsertTextUnit(String userId, String unitType, String canonicalContent, String scope,
                                String content, byte[] embedding, Long factId, String validFrom, String validTo,
                                String sourceType, String sourceId, String evidence, String logReason,
                                long batchSequence) {
        if (userId == null || userId.isBlank() || text(content).isBlank()) return;
        String key = unitType + "|" + normalize(canonicalContent);
        UnitState existing = findByCanonicalKey(userId, key);
        String id = existing == null ? stableId(unitType, userId, key + "|" + sourceId) : existing.id();
        if (existing != null && content.equals(existing.content()) && batchSequence == 0) {
            Boolean sourcePresent = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM memory_retrieval_source WHERE unit_id=? "
                + "AND source_type=? AND source_id=?)", Boolean.class, id, sourceType, sourceId);
            if (!Boolean.TRUE.equals(sourcePresent)) addSource(id, sourceType, sourceId, null, evidence, 0);
            return;
        }
        snapshotUnit(userId, id, batchSequence);
        int tokens = tokenCount(content);
        jdbc.update("INSERT INTO memory_retrieval_unit(id,user_id,canonical_key,unit_type,scope,status,searchable,content,"
                + "embedding,token_count,valid_from,valid_to,fact_id,compaction_version) "
                + "VALUES(?,?,?,?,?,'active',1,?,?,?,?,?,?,1) ON CONFLICT(id) DO UPDATE SET "
                + "scope=excluded.scope,status='active',searchable=1,content=excluded.content,"
                + "embedding=COALESCE(excluded.embedding,memory_retrieval_unit.embedding),token_count=excluded.token_count,"
                + "valid_from=excluded.valid_from,valid_to=excluded.valid_to,fact_id=COALESCE(excluded.fact_id,memory_retrieval_unit.fact_id),"
                + "compaction_version=memory_retrieval_unit.compaction_version+1,updated_at=CURRENT_TIMESTAMP",
            id, userId, key, unitType, scope, content, embedding, tokens, emptyToNull(validFrom), emptyToNull(validTo), factId);
        addSource(id, sourceType, sourceId, null, evidence, batchSequence);
        if (existing == null || !content.equals(existing.content())) {
            log(userId, batchSequence, existing == null ? "KEEP" : "MERGE", id, sourceId,
                existing == null ? 0 : existing.tokenCount(), tokens, logReason);
        }
    }

    private UnitCandidate loadCandidate(String userId, String unitId) {
        List<UnitCandidate> rows = jdbc.query(
            "SELECT id,canonical_key,unit_type,predicate,scope,status,content,token_count,valid_from,valid_to "
                + "FROM memory_retrieval_unit WHERE user_id=? AND id=? AND searchable=1 "
                + "AND (status='active' OR status='historical')",
            (rs, row) -> new UnitCandidate(rs.getString("id"), rs.getString("canonical_key"),
                rs.getString("unit_type"), rs.getString("predicate"), rs.getString("scope"), rs.getString("status"),
                rs.getString("content"), rs.getInt("token_count"), rs.getString("valid_from"), rs.getString("valid_to"), 0),
            userId, unitId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private UnitState findByCanonicalKey(String userId, String key) {
        List<UnitState> rows = jdbc.query("SELECT id,token_count,content FROM memory_retrieval_unit WHERE user_id=? AND canonical_key=? "
                + "AND status='active' AND searchable=1 ORDER BY updated_at DESC LIMIT 1",
            (rs, row) -> new UnitState(rs.getString("id"), rs.getInt("token_count"), rs.getString("content")),
            userId, key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private UnitState findByFactId(String userId, long factId) {
        List<UnitState> rows = jdbc.query("SELECT id,token_count,content FROM memory_retrieval_unit "
                + "WHERE user_id=? AND fact_id=? AND unit_type='fact' ORDER BY updated_at DESC LIMIT 1",
            (rs, row) -> new UnitState(rs.getString("id"), rs.getInt("token_count"), rs.getString("content")),
            userId, factId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String rowId(String sql, Object... args) {
        try {
            Object id = jdbc.queryForObject(sql, Object.class, args);
            return id == null ? "" : String.valueOf(id);
        } catch (Exception ignored) { return ""; }
    }

    private void addSource(String unitId, String sourceType, String sourceId, String turnId, String evidence) {
        addSource(unitId, sourceType, sourceId, turnId, evidence, 0);
    }

    private void addSource(String unitId, String sourceType, String sourceId, String turnId, String evidence,
                           long batchSequence) {
        if (sourceType == null || sourceType.isBlank() || sourceId == null || sourceId.isBlank()) return;
        snapshotSource(unitId, sourceType, sourceId, batchSequence);
        jdbc.update("INSERT INTO memory_retrieval_source(unit_id,source_type,source_id,source_turn_id,evidence_text) "
                + "VALUES(?,?,?,?,?) ON CONFLICT(unit_id,source_type,source_id) DO UPDATE SET "
                + "source_turn_id=COALESCE(excluded.source_turn_id,memory_retrieval_source.source_turn_id),"
                + "evidence_text=COALESCE(excluded.evidence_text,memory_retrieval_source.evidence_text)",
            unitId, sourceType, sourceId, emptyToNull(turnId), emptyToNull(evidence));
    }

    private void snapshotUnit(String userId, String unitId, long batchSequence) {
        if (batchSequence <= 0 || unitId == null || unitId.isBlank()) return;
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM memory_retrieval_unit WHERE user_id=? AND id=?",
            userId, unitId);
        remember(userId, batchSequence, "memory_retrieval_unit", unitId, rows.isEmpty() ? null : rows.get(0));
    }

    private void snapshotSource(String unitId, String sourceType, String sourceId, long batchSequence) {
        if (batchSequence <= 0) return;
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM memory_retrieval_source "
                + "WHERE unit_id=? AND source_type=? AND source_id=?", unitId, sourceType, sourceId);
        remember(unitUserId(unitId), batchSequence, "memory_retrieval_source",
            compositeKey(unitId, sourceType, sourceId), rows.isEmpty() ? null : rows.get(0));
    }

    private String unitUserId(String unitId) {
        return rowId("SELECT user_id FROM memory_retrieval_unit WHERE id=?", unitId);
    }

    private void snapshotLongTermMemory(String userId, String memoryId, long batchSequence) {
        if (batchSequence <= 0 || memoryId == null || memoryId.isBlank()) return;
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id,user_id,searchable FROM long_term_memory "
                + "WHERE user_id=? AND id=?", userId, memoryId);
        remember(userId, batchSequence, "long_term_memory", memoryId, rows.isEmpty() ? null : rows.get(0));
    }

    private void remember(String userId, long batchSequence, String entityType, String recordKey,
                          Map<String, Object> state) {
        if (userId == null || userId.isBlank() || batchSequence <= 0 || recordKey == null) return;
        String json = "";
        try {
            if (state != null) {
                Map<String, Object> compactState = new LinkedHashMap<>(state);
                if (compactState.get("embedding") instanceof byte[] embedding) {
                    String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(embedding));
                    jdbc.update("INSERT OR IGNORE INTO memory_compaction_blob(hash,value) VALUES(?,?)", hash, embedding);
                    compactState.remove("embedding");
                    compactState.put("embedding_ref", hash);
                }
                json = SNAPSHOT_MAPPER.writeValueAsString(compactState);
            }
        } catch (Exception error) {
            throw new IllegalStateException("无法保存馆长压缩回滚快照", error);
        }
        jdbc.update("INSERT OR IGNORE INTO memory_compaction_snapshot(user_id,batch_sequence,entity_type,record_key,"
                + "existed_before,state_json) VALUES(?,?,?,?,?,?)",
            userId, batchSequence, entityType, recordKey, state == null ? 0 : 1, json);
    }

    private void restoreSnapshot(String userId, String entityType, String recordKey,
                                 boolean existedBefore, String stateJson) {
        Map<String, Object> state = null;
        if (existedBefore) {
            try {
                state = SNAPSHOT_MAPPER.readValue(stateJson, new TypeReference<Map<String, Object>>() {});
                if (state.containsKey("embedding_ref")) {
                    List<byte[]> blobs = jdbc.query("SELECT value FROM memory_compaction_blob WHERE hash=?",
                        (rs, row) -> rs.getBytes(1), state.get("embedding_ref"));
                    if (blobs.isEmpty()) throw new IllegalStateException("压缩回滚向量快照缺失");
                    state.put("embedding", blobs.get(0));
                }
            } catch (Exception error) {
                throw new IllegalStateException("馆长压缩回滚快照损坏: " + entityType + "/" + recordKey, error);
            }
        }
        switch (entityType) {
            case "memory_retrieval_unit" -> restoreUnit(userId, recordKey, existedBefore, state);
            case "memory_retrieval_source" -> restoreSource(recordKey, existedBefore, state);
            case "long_term_memory" -> {
                if (existedBefore) jdbc.update("UPDATE long_term_memory SET searchable=? WHERE user_id=? AND id=?",
                    number(state.get("searchable")), userId, recordKey);
            }
            case "memory_fact" -> {
                if (existedBefore) {
                    jdbc.update("UPDATE memory_fact SET status=?,supersedes_id=?,valid_from=?,valid_to=?,observed_at=?,confidence=?,assertion=?,"
                            + "last_observed_at=?,evidence_count=?,updated_at=? "
                            + "WHERE user_id=? AND id=?", state.get("status"), state.get("supersedes_id"),
                        state.get("valid_from"), state.get("valid_to"), state.get("observed_at"), state.get("confidence"), state.get("assertion"),
                        state.get("last_observed_at"), state.getOrDefault("evidence_count", 1), state.get("updated_at"), userId, recordKey);
                } else {
                    jdbc.update("UPDATE memory_fact SET status='rolled_back',supersedes_id=NULL,updated_at=CURRENT_TIMESTAMP "
                        + "WHERE user_id=? AND id=?", userId, recordKey);
                }
            }
            case "user_profile_current" -> restoreProfile(userId, recordKey, existedBefore, state);
            case "user_insight" -> restoreInsight(userId, recordKey, existedBefore, state);
            case "llm_growth" -> restoreGrowth(userId, recordKey, existedBefore, state);
            case "curator_turn" -> jdbc.update("UPDATE curator_turns SET consolidation_status=?,processed_at=? WHERE user_id=? AND turn_id=?",
                state.get("consolidation_status"), state.get("processed_at"), userId, recordKey);
            case "curator_state" -> {
                if (!existedBefore) jdbc.update("DELETE FROM curator_state WHERE user_id=?", userId);
                else jdbc.update("UPDATE curator_state SET checkpoint=?,last_turn_id=?,last_success_at=?,last_error=?,"
                    + "retry_count=?,retry_after=?,working_memory_json=?,updated_at=? WHERE user_id=?",
                    state.get("checkpoint"), state.get("last_turn_id"), state.get("last_success_at"), state.get("last_error"),
                    state.get("retry_count"), state.get("retry_after"), state.get("working_memory_json"), state.get("updated_at"), userId);
            }
            default -> throw new IllegalStateException("未知的馆长压缩快照类型: " + entityType);
        }
    }

    private void restoreUnit(String userId, String unitId, boolean existedBefore, Map<String, Object> state) {
        if (!existedBefore) {
            jdbc.update("UPDATE memory_retrieval_unit SET searchable=0,status='rolled_back',"
                    + "compaction_version=compaction_version+1,updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND id=?",
                userId, unitId);
            return;
        }
        Object[] values = {state.get("user_id"), state.get("canonical_key"), state.get("unit_type"),
            state.get("predicate"), state.get("scope"), state.get("status"), state.get("searchable"),
            state.get("content"), blob(state.get("embedding")), state.get("token_count"), state.get("valid_from"),
            state.get("valid_to"), state.get("fact_id"), state.get("supersedes_unit_id"),
            state.get("compaction_version"), state.get("created_at"), state.get("updated_at"), unitId};
        jdbc.update("UPDATE memory_retrieval_unit SET user_id=?,canonical_key=?,unit_type=?,predicate=?,"
                + "scope=?,status=?,searchable=?,content=?,embedding=?,token_count=?,valid_from=?,valid_to=?,fact_id=?,"
                + "supersedes_unit_id=?,compaction_version=?,created_at=?,updated_at=? WHERE id=?", values);
        Boolean present = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM memory_retrieval_unit WHERE id=?)",
            Boolean.class, unitId);
        if (!Boolean.TRUE.equals(present)) {
            jdbc.update("INSERT INTO memory_retrieval_unit(id,user_id,canonical_key,unit_type,predicate,scope,status,"
                    + "searchable,content,embedding,token_count,valid_from,valid_to,fact_id,supersedes_unit_id,"
                    + "compaction_version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                prepend(unitId, java.util.Arrays.copyOf(values, values.length - 1)));
        }
    }

    private void restoreSource(String encodedKey, boolean existedBefore, Map<String, Object> state) {
        String[] key = splitCompositeKey(encodedKey, 3);
        if (!existedBefore) {
            jdbc.update("DELETE FROM memory_retrieval_source WHERE unit_id=? AND source_type=? AND source_id=?",
                (Object[]) key);
            return;
        }
        jdbc.update("INSERT INTO memory_retrieval_source(unit_id,source_type,source_id,source_turn_id,evidence_text,surface_value,value_start,value_end,created_at) "
                + "VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(unit_id,source_type,source_id) DO UPDATE SET "
                + "source_turn_id=excluded.source_turn_id,evidence_text=excluded.evidence_text,surface_value=excluded.surface_value,"
                + "value_start=excluded.value_start,value_end=excluded.value_end,created_at=excluded.created_at",
            state.get("unit_id"), state.get("source_type"), state.get("source_id"), state.get("source_turn_id"),
            state.get("evidence_text"),state.getOrDefault("surface_value",""),state.getOrDefault("value_start",-1),state.getOrDefault("value_end",-1),state.get("created_at"));
    }

    private void restoreProfile(String userId, String slot, boolean existedBefore, Map<String, Object> state) {
        if (!existedBefore) {
            jdbc.update("DELETE FROM user_profile_current WHERE user_id=? AND slot_key=?", userId, slot);
            return;
        }
        jdbc.update("INSERT INTO user_profile_current(user_id,slot_key,value,source_fact_id,confidence,valid_from,valid_to,updated_at) "
                + "VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(user_id,slot_key) DO UPDATE SET value=excluded.value,"
                + "source_fact_id=excluded.source_fact_id,confidence=excluded.confidence,valid_from=excluded.valid_from,"
                + "valid_to=excluded.valid_to,updated_at=excluded.updated_at",
            state.get("user_id"), state.get("slot_key"), state.get("value"), state.get("source_fact_id"),
            state.get("confidence"), state.get("valid_from"), state.get("valid_to"), state.get("updated_at"));
    }

    private void restoreInsight(String userId, String insight, boolean existedBefore, Map<String, Object> state) {
        if (!existedBefore) {
            jdbc.update("DELETE FROM user_insight WHERE user_id=? AND insight=?", userId, insight);
            return;
        }
        jdbc.update("INSERT INTO user_insight(id,user_id,insight,context,embedding,created_at) VALUES(?,?,?,?,?,?) "
                + "ON CONFLICT(id) DO UPDATE SET user_id=excluded.user_id,insight=excluded.insight,"
                + "context=excluded.context,embedding=excluded.embedding,created_at=excluded.created_at",
            state.get("id"), state.get("user_id"), state.get("insight"), state.get("context"),
            blob(state.get("embedding")), state.get("created_at"));
    }

    private void restoreGrowth(String userId, String encodedKey, boolean existedBefore, Map<String, Object> state) {
        String[] key = splitCompositeKey(encodedKey, 2);
        if (!existedBefore) {
            jdbc.update("DELETE FROM llm_growth WHERE user_id=? AND category=? AND insight=?",
                userId, key[0], key[1]);
            return;
        }
        jdbc.update("INSERT INTO llm_growth(id,user_id,category,insight,context,embedding,title,source_type,source_id,created_at,updated_at) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET user_id=excluded.user_id,"
                + "category=excluded.category,insight=excluded.insight,context=excluded.context,embedding=excluded.embedding,"
                + "title=excluded.title,source_type=excluded.source_type,source_id=excluded.source_id,"
                + "created_at=excluded.created_at,updated_at=excluded.updated_at",
            state.get("id"), state.get("user_id"), state.get("category"), state.get("insight"), state.get("context"),
            blob(state.get("embedding")), state.get("title"), state.get("source_type"), state.get("source_id"),
            state.get("created_at"), state.get("updated_at"));
    }

    private static byte[] blob(Object value) {
        if (value instanceof byte[] bytes) return bytes;
        if (value instanceof String text && !text.isBlank()) return Base64.getDecoder().decode(text);
        return null;
    }

    private static Object[] prepend(Object first, Object[] rest) {
        Object[] values = new Object[rest.length + 1];
        values[0] = first;
        System.arraycopy(rest, 0, values, 1, rest.length);
        return values;
    }

    private static String compositeKey(String... values) {
        String joined = String.join("\u001f", values);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(joined.getBytes(StandardCharsets.UTF_8));
    }

    private static String[] splitCompositeKey(String value, int expectedParts) {
        String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        String[] parts = decoded.split("\u001f", -1);
        if (parts.length != expectedParts) throw new IllegalArgumentException("馆长压缩快照主键损坏");
        return parts;
    }

    private void log(String userId, long batchSequence, String action, String unitId, String sourceId,
                     int before, int after, String reason) {
        jdbc.update("INSERT INTO memory_compaction_log(user_id,batch_sequence,action,unit_id,source_id,"
                + "tokens_before,tokens_after,reason) VALUES(?,?,?,?,?,?,?,?)",
            userId, Math.max(0, batchSequence), action, unitId, sourceId == null ? "" : sourceId,
            Math.max(0, before), Math.max(0, after), reason == null ? "" : reason);
    }

    private double matchScore(String content, String query) {
        String normalizedContent = normalize(content);
        String normalizedQuery = normalize(query);
        if (normalizedQuery.isBlank() || normalizedContent.isBlank()) return 0;
        if (normalizedContent.contains(normalizedQuery)) return 1.0;
        int hits = 0;
        int total = 0;
        for (int len = 2; len <= 4; len++) {
            for (int i = 0; i + len <= normalizedQuery.length(); i++) {
                String gram = normalizedQuery.substring(i, i + len);
                if (gram.codePoints().allMatch(Character::isWhitespace)) continue;
                total++;
                if (normalizedContent.contains(gram)) hits++;
            }
        }
        return total == 0 || hits == 0 ? 0 : 0.15 + 0.7 * ((double) hits / total);
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
            .toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "").trim();
    }

    private static String stableId(String type, String userId, String key) {
        return UUID.nameUUIDFromBytes((type + "\n" + userId + "\n" + key).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String scopeLabel(String scope, String status) {
        if ("historical".equals(status)) return "历史";
        return switch (scope == null ? "" : scope) {
            case "current" -> "当前";
            case "stable" -> "稳定";
            case "planned" -> "计划";
            case "episodic" -> "事件";
            case "historical" -> "历史";
            default -> "长期";
        };
    }

    private static boolean containsAny(String value, String... options) {
        for (String option : options) if (value.contains(option)) return true;
        return false;
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) return value == null ? "" : value;
        return value.substring(0, max);
    }

    private static String emptyToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private static long number(Object value) { return value instanceof Number n ? n.longValue() : 0L; }
    private static byte[] bytes(Object value) { return value instanceof byte[] bytes ? bytes : null; }

    private record UnitState(String id, int tokenCount, String content) {}
    private record UnitCandidate(String id, String canonicalKey, String unitType, String predicate, String scope,
                                 String status, String content, int tokenCount, String validFrom, String validTo,
                                 double score) {
        UnitCandidate withScore(double value) {
            return new UnitCandidate(id, canonicalKey, unitType, predicate, scope, status, content,
                tokenCount, validFrom, validTo, value);
        }
    }
}
