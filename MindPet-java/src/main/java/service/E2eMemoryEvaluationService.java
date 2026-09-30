package service;

import config.EvaluationSqlitePathGuard;
import model.E2eMemoryIngestResult;
import model.EvaluationWriteTrace;
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

@Service
public class E2eMemoryEvaluationService {
    public static final String EVAL_USER = "e2e_memory_eval_user";

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    private final JdbcTemplate jdbc;
    private final KnowledgeGraphService knowledgeGraph;
    private final String storagePath;
    private final String evaluationPath;
    private final String allowedRoot;
    private final String userHome;
    private final long completionTimeoutMs;

    public E2eMemoryEvaluationService(
            JdbcTemplate jdbc,
            KnowledgeGraphService knowledgeGraph,
            @Value("${app.storage.sqlite.path:${MINDPET_DATA_DIR:${user.home}/.mindpet}/mindpet.db}")
            String storagePath,
            @Value("${app.eval.e2e-memory.sqlite-path:${APP_EVAL_E2E_MEMORY_SQLITE_PATH:}}")
            String evaluationPath,
            @Value("${app.eval.e2e-memory.allowed-root:${APP_EVAL_E2E_MEMORY_ALLOWED_ROOT:}}")
            String allowedRoot,
            @Value("${user.home}") String userHome,
            @Value("${app.eval.e2e-memory.completion-timeout-ms:180000}") long completionTimeoutMs) {
        this.jdbc = jdbc;
        this.knowledgeGraph = knowledgeGraph;
        this.storagePath = storagePath;
        this.evaluationPath = evaluationPath;
        this.allowedRoot = allowedRoot;
        this.userHome = userHome;
        this.completionTimeoutMs = completionTimeoutMs;
    }

    public E2eMemoryIngestResult ingest(
            String sampleId,
            String runId,
            String userMessage,
            String assistantContext,
            String emotion,
            Instant occurredAt) {
        requireSafeDatabase();
        validateIds(sampleId, runId);
        if (userMessage == null || userMessage.isBlank() || assistantContext == null) {
            throw failure("VALIDATION", "INVALID_INPUT", "userMessage must be nonblank and assistantContext textual", null);
        }
        String sessionId = "e2e:" + runId + ":" + sampleId;
        if (sessionId.length() > 240) {
            throw failure("VALIDATION", "SESSION_ID_TOO_LONG", "Evaluation session id is too long", null);
        }
        String safeEmotion = emotion == null || emotion.isBlank() ? "neutral" : emotion;
        Instant safeOccurredAt = occurredAt == null ? Instant.now() : occurredAt;
        List<String> ltmBefore = allLtmIds();

        KnowledgeGraphService.CompletedTurnResult completed;
        try {
            completed = knowledgeGraph.onCompletedTurnForEvaluation(
                EVAL_USER, sessionId, userMessage, assistantContext, safeEmotion, safeOccurredAt)
                .get(completionTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            throw failure("COMPLETION", "COMPLETION_TIMEOUT", "Completed-turn evaluation timed out", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure("COMPLETION", "COMPLETION_INTERRUPTED", "Completed-turn evaluation was interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof KnowledgeGraphService.CompletedTurnFailure pipelineFailure) {
                throw failure(pipelineFailure.stage(), pipelineFailure.type(), pipelineFailure.getMessage(),
                    pipelineFailure, failedWriteTrace(sampleId, sessionId, pipelineFailure));
            }
            throw failure("COMPLETION", "COMPLETION_FAILED", "Completed-turn evaluation failed", cause);
        }

        List<String> ltmAfter = allLtmIds();
        List<String> ltmCreated = difference(ltmAfter, ltmBefore);
        List<E2eMemoryIngestResult.EntityRow> entities = entityRows(completed.turnHash());
        List<E2eMemoryIngestResult.RelationRow> relations = relationRows(completed.turnHash());
        List<String> evidenceIds = evidenceIds(completed.turnHash());
        boolean turnIngestRecorded = count(
            "SELECT COUNT(*) FROM kg_turn_ingest WHERE user_id=? AND turn_hash=?",
            EVAL_USER, completed.turnHash()) > 0;
        boolean ltmPersisted = completed.ltmAttempted() && !ltmCreated.isEmpty();
        boolean pruneOccurred = ltmAfter.size() - ltmBefore.size() < ltmCreated.size();
        int pruneDeletedEstimate = Math.max(0, ltmBefore.size() + ltmCreated.size() - ltmAfter.size());

        String status;
        String errorStage = null;
        String errorType = null;
        String errorMessage = null;
        if (completed.duplicate()) {
            status = "NO_PERSIST";
        } else if (!turnIngestRecorded) {
            status = "FAILED";
            errorStage = "KG_PERSISTENCE";
            errorType = "TURN_INGEST_NOT_RECORDED";
            errorMessage = "Completed processor returned without a turn-ingest marker";
        } else if (completed.ltmAttempted() && !ltmPersisted) {
            status = "KG_ONLY_PARTIAL_SUCCESS";
            errorStage = "LTM_PERSISTENCE";
            errorType = "EXPECTED_ROW_NOT_FOUND";
            errorMessage = "Long-term-memory persistence was selected but no row was observed";
        } else if (ltmPersisted || !entities.isEmpty() || !relations.isEmpty() || !evidenceIds.isEmpty()) {
            status = "FULL_SUCCESS";
        } else {
            status = "NO_PERSIST";
        }

        List<String> entityIds = entities.stream().map(E2eMemoryIngestResult.EntityRow::id).toList();
        List<String> relationIds = relations.stream().map(E2eMemoryIngestResult.RelationRow::id).toList();
        int entityCount = completed.duplicate() ? 0 : entities.size();
        int relationCount = completed.duplicate() ? 0 : relations.size();
        int evidenceCount = completed.duplicate() ? 0 : evidenceIds.size();
        EvaluationWriteTrace writeTrace = writeTrace(
            sampleId, sessionId, completed, ltmPersisted, ltmCreated,
            entityIds, relationIds, evidenceIds, entityCount, relationCount, evidenceCount);
        return new E2eMemoryIngestResult(
            status, sampleId, runId, EVAL_USER, sessionId, completed.turnHash(), completed.model(),
            completed.duplicate(), true, completed.extractionCompleted(), completed.shouldRemember(),
            completed.importance(), completed.confidence(), completed.ltmAttempted(), ltmPersisted,
            entityCount, relationCount, evidenceCount, turnIngestRecorded,
            ltmBefore.size(), ltmAfter.size(), pruneOccurred, pruneDeletedEstimate,
            new E2eMemoryIngestResult.RowMapping(ltmCreated, entityIds, relationIds, evidenceIds),
            entities, relations, writeTrace, errorStage, errorType, errorMessage);
    }

    private EvaluationWriteTrace writeTrace(
            String sampleId,
            String sessionId,
            KnowledgeGraphService.CompletedTurnResult completed,
            boolean ltmPersisted,
            List<String> ltmIds,
            List<String> entityIds,
            List<String> relationIds,
            List<String> evidenceIds,
            int persistedEntityCount,
            int persistedRelationCount,
            int evidenceCount) {
        KnowledgeGraphService.DecisionDiagnostics decision = completed.decisionDiagnostics();
        KnowledgeGraphService.ParseDiagnostics parse = completed.parseDiagnostics();
        KnowledgeGraphService.KgFilterDiagnostics kg = completed.kgFilterDiagnostics();
        KnowledgeGraphService.TemporalDiagnostics temporal = completed.temporalDiagnostics();
        if (decision == null || parse == null || kg == null || temporal == null) return null;

        String ltmFailureReason = completed.ltmAttempted() && !ltmPersisted
            ? "EXPECTED_ROW_NOT_FOUND" : null;
        return new EvaluationWriteTrace(
            new EvaluationWriteTrace.DecisionTrace(
                decision.rawWorthRemembering(), decision.rawMemoryShouldRemember(),
                decision.combinedShouldRemember(), decision.importance(), decision.confidence(),
                decision.importanceThreshold(), decision.confidenceThreshold(),
                decision.importanceGatePassed(), decision.confidenceGatePassed(),
                completed.ltmAttempted(), ltmPersisted, ltmFailureReason),
            new EvaluationWriteTrace.ParseDiagnostics(
                parse.memoryObjectPresent(), parse.importanceFallbackUsed(),
                parse.confidenceFallbackUsed(), parse.importanceClamped(),
                parse.confidenceClamped(), parse.parseFailure(), parse.parseFailureReason()),
            new EvaluationWriteTrace.KgFilterTrace(
                kg.rawEntityCount(), kg.rawRelationCount(),
                kg.normalizedEntityCount(), kg.normalizedRelationCount(),
                kg.sensitivityRejectedEntityCount(), kg.predicateWhitelistRejectedCount(),
                kg.relationConfidenceRejectedCount(), persistedEntityCount,
                persistedRelationCount, evidenceCount),
            new EvaluationWriteTrace.TemporalTrace(
                temporal.eventDate(), temporal.eventAt(), temporal.eventTimezone(),
                temporal.eventPrecision(), temporal.referenceTimestamp(),
                temporal.referenceTimezone()),
            new EvaluationWriteTrace.ProvenanceTrace(
                sampleId, completed.turnHash(), sessionId, null, null,
                entityIds, relationIds, evidenceIds, ltmIds));
    }

    public E2eMemoryIngestResult.Snapshot snapshot() {
        EvaluationSqlitePathGuard.ValidatedPaths guard = requireSafeDatabase();
        Map<String, E2eMemoryIngestResult.TableSnapshot> tables = new LinkedHashMap<>();
        tables.put("long_term_memory", tableSnapshot(
            "SELECT id,session_id,content,role,importance,confidence,layer,emotion,event_date,event_at,created_at "
                + "FROM long_term_memory WHERE user_id=? ORDER BY id"));
        tables.put("kg_entity", tableSnapshot(
            "SELECT id,normalized_name,display_name,entity_type,summary,importance,mention_count,first_seen,last_seen "
                + "FROM kg_entity WHERE user_id=? ORDER BY id"));
        tables.put("kg_relation", tableSnapshot(
            "SELECT r.id,r.source_entity_id,s.normalized_name AS source_name,s.entity_type AS source_type,"
                + "r.target_entity_id,t.normalized_name AS target_name,t.entity_type AS target_type,"
                + "r.predicate,r.confidence,r.importance,r.mention_count,r.first_seen,r.last_seen "
                + "FROM kg_relation r JOIN kg_entity s ON s.id=r.source_entity_id "
                + "JOIN kg_entity t ON t.id=r.target_entity_id WHERE r.user_id=? ORDER BY r.id"));
        tables.put("kg_evidence", tableSnapshot(
            "SELECT id,turn_hash,entity_id,relation_id,session_id,user_message,assistant_message,created_at "
                + "FROM kg_evidence WHERE user_id=? ORDER BY id"));
        tables.put("kg_turn_ingest", tableSnapshot(
            "SELECT turn_hash,session_id,entity_count,relation_count,created_at "
                + "FROM kg_turn_ingest WHERE user_id=? ORDER BY turn_hash"));
        return new E2eMemoryIngestResult.Snapshot(
            "OK", guard.databasePath().toString(), EVAL_USER,
            knowledgeGraph.effectiveModelForEvaluation(), knowledgeGraph.extractionPromptSha256(), true, tables);
    }

    @Transactional
    public E2eMemoryIngestResult.ResetResult reset() {
        EvaluationSqlitePathGuard.ValidatedPaths guard = requireSafeDatabase();
        Map<String, Integer> deleted = new LinkedHashMap<>();
        deleted.put("kg_evidence", jdbc.update("DELETE FROM kg_evidence WHERE user_id=?", EVAL_USER));
        deleted.put("kg_relation", jdbc.update("DELETE FROM kg_relation WHERE user_id=?", EVAL_USER));
        deleted.put("kg_entity", jdbc.update("DELETE FROM kg_entity WHERE user_id=?", EVAL_USER));
        deleted.put("kg_turn_ingest", jdbc.update("DELETE FROM kg_turn_ingest WHERE user_id=?", EVAL_USER));
        deleted.put("long_term_memory", jdbc.update("DELETE FROM long_term_memory WHERE user_id=?", EVAL_USER));
        E2eMemoryIngestResult.Snapshot after = snapshot();
        if (after.tables().values().stream().anyMatch(table -> table.count() != 0)) {
            throw failure("RESET", "RESET_INCOMPLETE", "Evaluation rows remain after reset", null);
        }
        return new E2eMemoryIngestResult.ResetResult(
            "OK", guard.databasePath().toString(), EVAL_USER, deleted, after);
    }

    EvaluationSqlitePathGuard.ValidatedPaths requireSafeDatabase() {
        try {
            return EvaluationSqlitePathGuard.validate(
                storagePath, evaluationPath, allowedRoot, userHome);
        } catch (EvaluationSqlitePathGuard.GuardFailure guardFailure) {
            throw failure("DATABASE_GUARD", guardFailure.type(), guardFailure.getMessage(), guardFailure);
        }
    }

    private E2eMemoryIngestResult.TableSnapshot tableSnapshot(String sql) {
        List<Map<String, Object>> rows = jdbc.query(sql, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            int columns = rs.getMetaData().getColumnCount();
            for (int index = 1; index <= columns; index++) {
                row.put(rs.getMetaData().getColumnLabel(index), rs.getObject(index));
            }
            return row;
        }, EVAL_USER);
        return new E2eMemoryIngestResult.TableSnapshot(rows.size(), rows);
    }

    private List<String> allLtmIds() {
        return jdbc.query(
            "SELECT id FROM long_term_memory WHERE user_id=? ORDER BY id",
            (rs, rowNum) -> rs.getString(1), EVAL_USER);
    }

    private List<E2eMemoryIngestResult.EntityRow> entityRows(String turnHash) {
        return jdbc.query(
            "SELECT DISTINCT e.id,e.normalized_name,e.display_name,e.entity_type "
                + "FROM kg_evidence v JOIN kg_entity e ON e.id=v.entity_id "
                + "WHERE v.user_id=? AND v.turn_hash=? ORDER BY e.id",
            (rs, rowNum) -> new E2eMemoryIngestResult.EntityRow(
                rs.getString("id"), rs.getString("normalized_name"),
                rs.getString("display_name"), rs.getString("entity_type")),
            EVAL_USER, turnHash);
    }

    private List<E2eMemoryIngestResult.RelationRow> relationRows(String turnHash) {
        return jdbc.query(
            "SELECT DISTINCT r.id,r.source_entity_id,s.normalized_name AS source_name,s.entity_type AS source_type,"
                + "r.target_entity_id,t.normalized_name AS target_name,t.entity_type AS target_type,r.predicate "
                + "FROM kg_evidence v JOIN kg_relation r ON r.id=v.relation_id "
                + "JOIN kg_entity s ON s.id=r.source_entity_id JOIN kg_entity t ON t.id=r.target_entity_id "
                + "WHERE v.user_id=? AND v.turn_hash=? ORDER BY r.id",
            (rs, rowNum) -> new E2eMemoryIngestResult.RelationRow(
                rs.getString("id"), rs.getString("source_entity_id"),
                rs.getString("source_name"), rs.getString("source_type"),
                rs.getString("target_entity_id"), rs.getString("target_name"),
                rs.getString("target_type"), rs.getString("predicate")),
            EVAL_USER, turnHash);
    }

    private List<String> evidenceIds(String turnHash) {
        return jdbc.query(
            "SELECT id FROM kg_evidence WHERE user_id=? AND turn_hash=? ORDER BY id",
            (rs, rowNum) -> rs.getString(1), EVAL_USER, turnHash);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private List<String> difference(List<String> after, List<String> before) {
        Set<String> existing = new LinkedHashSet<>(before);
        return after.stream().filter(id -> !existing.contains(id)).toList();
    }

    private void validateIds(String sampleId, String runId) {
        if (sampleId == null || !SAFE_ID.matcher(sampleId).matches()) {
            throw failure("VALIDATION", "INVALID_SAMPLE_ID", "sampleId is invalid", null);
        }
        if (runId == null || !SAFE_ID.matcher(runId).matches()) {
            throw failure("VALIDATION", "INVALID_RUN_ID", "runId is invalid", null);
        }
    }

    private EvaluationFailure failure(String stage, String type, String message, Throwable cause) {
        return failure(stage, type, message, cause, null);
    }

    private EvaluationFailure failure(String stage, String type, String message, Throwable cause,
                                      EvaluationWriteTrace writeTrace) {
        return new EvaluationFailure(stage, type, message, cause, writeTrace);
    }

    private EvaluationWriteTrace failedWriteTrace(
            String sampleId, String sessionId,
            KnowledgeGraphService.CompletedTurnFailure failure) {
        KnowledgeGraphService.ParseDiagnostics parse = failure.parseDiagnostics();
        KnowledgeGraphService.TemporalDiagnostics temporal = failure.temporalDiagnostics();
        if (parse == null || temporal == null) return null;
        return new EvaluationWriteTrace(
            new EvaluationWriteTrace.DecisionTrace(
                null, null, null, null, null, 0.35, 0.45,
                null, null, false, false, null),
            new EvaluationWriteTrace.ParseDiagnostics(
                parse.memoryObjectPresent(), parse.importanceFallbackUsed(),
                parse.confidenceFallbackUsed(), parse.importanceClamped(),
                parse.confidenceClamped(), parse.parseFailure(), parse.parseFailureReason()),
            new EvaluationWriteTrace.KgFilterTrace(
                null, null, null, null, null, null, null, 0, 0, 0),
            new EvaluationWriteTrace.TemporalTrace(
                temporal.eventDate(), temporal.eventAt(), temporal.eventTimezone(),
                temporal.eventPrecision(), temporal.referenceTimestamp(),
                temporal.referenceTimezone()),
            new EvaluationWriteTrace.ProvenanceTrace(
                sampleId, failure.turnHash(), sessionId, null, null,
                List.of(), List.of(), List.of(), List.of()));
    }

    public static final class EvaluationFailure extends RuntimeException {
        private final String stage;
        private final String type;
        private final EvaluationWriteTrace writeTrace;

        public EvaluationFailure(String stage, String type, String message, Throwable cause,
                                 EvaluationWriteTrace writeTrace) {
            super(message, cause);
            this.stage = stage;
            this.type = type;
            this.writeTrace = writeTrace;
        }

        public String stage() { return stage; }
        public String type() { return type; }
        public EvaluationWriteTrace writeTrace() { return writeTrace; }
    }
}
