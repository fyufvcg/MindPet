package service;

import model.E2eMemoryIngestResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Evaluation-only orchestration around the real production completed-turn processor.
 * It adds database guards and observations but does not implement extraction or persistence rules.
 */
@Service
public class E2eMemoryEvaluationService {
    public static final String REQUIRED_DATABASE = "mindpet_e2e_eval";
    public static final String EVAL_USER = "e2e_memory_eval_user";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    private static final List<String> TABLES = List.of(
        "long_term_memory", "kg_entity", "kg_relation", "kg_evidence", "kg_turn_ingest");

    private final JdbcTemplate jdbc;
    private final KnowledgeGraphService knowledgeGraph;
    private final long completionTimeoutMs;

    public E2eMemoryEvaluationService(
            JdbcTemplate jdbc,
            KnowledgeGraphService knowledgeGraph,
            @Value("${app.eval.e2e-memory.completion-timeout-ms:180000}") long completionTimeoutMs) {
        this.jdbc = jdbc;
        this.knowledgeGraph = knowledgeGraph;
        this.completionTimeoutMs = completionTimeoutMs;
    }

    public E2eMemoryIngestResult ingest(
            String sampleId, String runId, String requestedUserId,
            String userMessage, String assistantContext, String emotion, Instant occurredAt) {
        validateIds(sampleId, runId);
        requireEvalUser(requestedUserId);
        requireDatabase();
        if (userMessage == null || userMessage.isBlank() || assistantContext == null) {
            throw new EvaluationFailure("VALIDATION", "INVALID_INPUT",
                "userMessage must be nonblank and assistantContext must be text", null);
        }

        String sessionId = "e2e:" + runId + ":" + sampleId;
        if (sessionId.length() > 128) {
            throw new EvaluationFailure("VALIDATION", "SESSION_ID_TOO_LONG",
                "Derived sessionId exceeds the production long-term memory limit", null);
        }

        Set<String> allLtmIdsBefore = new LinkedHashSet<>(allLtmIds());
        int ltmRowsBefore = allLtmIdsBefore.size();
        Set<String> ltmIdsBefore = new LinkedHashSet<>(ltmIds(sessionId, userMessage));
        Set<String> entityIdsBefore = Set.of();
        Set<String> relationIdsBefore = Set.of();
        Set<String> evidenceIdsBefore = Set.of();

        KnowledgeGraphService.CompletedTurnResult completed;
        try {
            completed = knowledgeGraph.onCompletedTurnForEvaluation(
                EVAL_USER, sessionId, userMessage, assistantContext,
                emotion == null || emotion.isBlank() ? "neutral" : emotion,
                occurredAt == null ? Instant.now() : occurredAt
            ).get(completionTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new EvaluationFailure("COMPLETION", "COMPLETION_TIMEOUT",
                "Production completed-turn processing did not finish before the evaluation timeout", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EvaluationFailure("COMPLETION", "COMPLETION_INTERRUPTED",
                "Evaluation completion wait was interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof KnowledgeGraphService.CompletedTurnFailure failure) {
                throw new EvaluationFailure(failure.stage(), failure.type(), failure.getMessage(), failure);
            }
            throw new EvaluationFailure("COMPLETION", "COMPLETION_FAILED",
                "Production completed-turn processing failed", cause);
        }

        String turnHash = completed.turnHash();
        if (completed.duplicate()) {
            entityIdsBefore = new LinkedHashSet<>(entityIds(turnHash));
            relationIdsBefore = new LinkedHashSet<>(relationIds(turnHash));
            evidenceIdsBefore = new LinkedHashSet<>(evidenceIds(turnHash));
        }

        List<String> currentLtmIds = ltmIds(sessionId, userMessage);
        Set<String> allLtmIdsAfter = new LinkedHashSet<>(allLtmIds());
        List<String> currentEntityIds = entityIds(turnHash);
        List<String> currentRelationIds = relationIds(turnHash);
        List<String> currentEvidenceIds = evidenceIds(turnHash);
        boolean turnIngestRecorded = count(
            "SELECT COUNT(*) FROM kg_turn_ingest WHERE user_id=? AND turn_hash=?",
            EVAL_USER, turnHash) > 0;
        int ltmRowsAfter = allLtmIdsAfter.size();

        int newLtmRows = differenceCount(currentLtmIds, ltmIdsBefore);
        int entityTouched = differenceCount(currentEntityIds, entityIdsBefore);
        int relationTouched = differenceCount(currentRelationIds, relationIdsBefore);
        int evidenceCreated = differenceCount(currentEvidenceIds, evidenceIdsBefore);
        boolean ltmPersisted = completed.ltmAttempted() && newLtmRows > 0;
        Set<String> deletedLtmIds = new LinkedHashSet<>(allLtmIdsBefore);
        deletedLtmIds.removeAll(allLtmIdsAfter);
        int pruneDeletedEstimate = deletedLtmIds.size();
        boolean pruneOccurred = pruneDeletedEstimate > 0;

        String status;
        String errorStage = null;
        String errorType = null;
        String message = null;
        if (completed.duplicate()) {
            status = "NO_PERSIST";
        } else if (!turnIngestRecorded) {
            status = "FAILED";
            errorStage = "KG_PERSISTENCE";
            errorType = "TURN_INGEST_NOT_RECORDED";
            message = "Completed processor returned without a turn-ingest marker";
        } else if (!completed.ltmAttempted()) {
            status = "NO_PERSIST";
        } else if (ltmPersisted) {
            status = "FULL_SUCCESS";
        } else {
            status = "KG_ONLY_PARTIAL_SUCCESS";
            errorStage = "LTM_PERSISTENCE";
            errorType = "EXPECTED_ROW_NOT_FOUND";
            message = "Production threshold requested LTM persistence but no traceable row was found";
        }

        return new E2eMemoryIngestResult(
            status, sampleId, runId, EVAL_USER, sessionId, turnHash, completed.model(),
            completed.duplicate(), true, completed.extractionCompleted(),
            completed.worthRemembering(), completed.shouldRemember(),
            completed.importance(), completed.confidence(),
            completed.ltmAttempted(), ltmPersisted,
            entityTouched, relationTouched, evidenceCreated, turnIngestRecorded,
            ltmRowsBefore, ltmRowsAfter, pruneOccurred, pruneDeletedEstimate,
            new E2eMemoryIngestResult.RowMapping(
                currentLtmIds, currentEntityIds, currentRelationIds, currentEvidenceIds),
            new E2eMemoryIngestResult.ParseInfo(
                completed.memoryObjectPresent(), completed.importanceFallbackUsed(),
                completed.confidenceFallbackUsed(), completed.importanceClamped(),
                completed.confidenceClamped()),
            errorStage, errorType, message);
    }

    public E2eMemoryIngestResult.Snapshot snapshot(String requestedUserId) {
        requireEvalUser(requestedUserId);
        String database = requireDatabase();
        Map<String, E2eMemoryIngestResult.TableCount> counts = new LinkedHashMap<>();
        for (String table : TABLES) {
            long total = count("SELECT COUNT(*) FROM " + table);
            long evalUser = count("SELECT COUNT(*) FROM " + table + " WHERE user_id=?", EVAL_USER);
            counts.put(table, new E2eMemoryIngestResult.TableCount(total, evalUser));
        }
        return new E2eMemoryIngestResult.Snapshot("OK", database, EVAL_USER, counts);
    }

    @Transactional
    public E2eMemoryIngestResult.ResetResult reset(String requestedUserId) {
        requireEvalUser(requestedUserId);
        String database = requireDatabase();
        Map<String, Integer> deleted = new LinkedHashMap<>();
        deleted.put("kg_evidence", jdbc.update(
            "DELETE FROM kg_evidence WHERE user_id=?", EVAL_USER));
        deleted.put("kg_relation", jdbc.update(
            "DELETE FROM kg_relation WHERE user_id=?", EVAL_USER));
        deleted.put("kg_entity", jdbc.update(
            "DELETE FROM kg_entity WHERE user_id=?", EVAL_USER));
        deleted.put("kg_turn_ingest", jdbc.update(
            "DELETE FROM kg_turn_ingest WHERE user_id=?", EVAL_USER));
        deleted.put("long_term_memory", jdbc.update(
            "DELETE FROM long_term_memory WHERE user_id=?", EVAL_USER));
        E2eMemoryIngestResult.Snapshot after = snapshot(EVAL_USER);
        boolean clean = after.tables().values().stream().allMatch(value -> value.evalUser() == 0);
        if (!clean) {
            throw new EvaluationFailure("RESET", "RESET_INCOMPLETE",
                "Evaluation rows remain after reset", null);
        }
        return new E2eMemoryIngestResult.ResetResult("OK", database, EVAL_USER, deleted, after);
    }

    private String requireDatabase() {
        String database;
        try {
            database = jdbc.queryForObject("SELECT current_database()", String.class);
        } catch (Exception e) {
            throw new EvaluationFailure("DATABASE_GUARD", "DATABASE_CHECK_FAILED",
                "Could not verify the evaluation database", e);
        }
        if (!REQUIRED_DATABASE.equals(database)) {
            throw new EvaluationFailure("DATABASE_GUARD", "WRONG_DATABASE",
                "Evaluation writes require database " + REQUIRED_DATABASE, null);
        }
        return database;
    }

    private void requireEvalUser(String userId) {
        if (!EVAL_USER.equals(userId)) {
            throw new EvaluationFailure("VALIDATION", "USER_NOT_ALLOWED",
                "Only the fixed E2E evaluation user is allowed", null);
        }
    }

    private void validateIds(String sampleId, String runId) {
        if (sampleId == null || !SAFE_ID.matcher(sampleId).matches()
                || runId == null || !SAFE_ID.matcher(runId).matches()) {
            throw new EvaluationFailure("VALIDATION", "INVALID_SAMPLE_ID",
                "sampleId and runId must match the evaluation identifier policy", null);
        }
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private List<String> ltmIds(String sessionId, String userMessage) {
        return jdbc.query(
            "SELECT id::text FROM long_term_memory WHERE user_id=? AND session_id=? "
                + "AND content=? AND role='user' ORDER BY id",
            (rs, rowNum) -> rs.getString(1), EVAL_USER, sessionId, userMessage);
    }

    private List<String> allLtmIds() {
        return jdbc.query(
            "SELECT id::text FROM long_term_memory WHERE user_id=? ORDER BY id",
            (rs, rowNum) -> rs.getString(1), EVAL_USER);
    }

    private List<String> entityIds(String turnHash) {
        return jdbc.query(
            "SELECT DISTINCT entity_id FROM kg_evidence WHERE user_id=? AND turn_hash=? "
                + "AND entity_id IS NOT NULL ORDER BY entity_id",
            (rs, rowNum) -> rs.getString(1), EVAL_USER, turnHash);
    }

    private List<String> relationIds(String turnHash) {
        return jdbc.query(
            "SELECT DISTINCT relation_id FROM kg_evidence WHERE user_id=? AND turn_hash=? "
                + "AND relation_id IS NOT NULL ORDER BY relation_id",
            (rs, rowNum) -> rs.getString(1), EVAL_USER, turnHash);
    }

    private List<String> evidenceIds(String turnHash) {
        return jdbc.query(
            "SELECT id::text FROM kg_evidence WHERE user_id=? AND turn_hash=? ORDER BY id",
            (rs, rowNum) -> rs.getString(1), EVAL_USER, turnHash);
    }

    private int differenceCount(List<String> after, Set<String> before) {
        Set<String> difference = new LinkedHashSet<>(after);
        difference.removeAll(before);
        return difference.size();
    }

    public static final class EvaluationFailure extends RuntimeException {
        private final String stage;
        private final String type;

        public EvaluationFailure(String stage, String type, String message, Throwable cause) {
            super(message, cause);
            this.stage = stage;
            this.type = type;
        }

        public String stage() { return stage; }
        public String type() { return type; }
    }
}
