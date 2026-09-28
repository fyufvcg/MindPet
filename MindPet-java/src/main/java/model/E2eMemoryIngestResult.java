package model;

import java.util.List;
import java.util.Map;

/** Evaluation-only diagnostics for one real production completed-turn ingestion. */
public record E2eMemoryIngestResult(
    String status,
    String sampleId,
    String runId,
    String userId,
    String sessionId,
    String turnHash,
    String model,
    boolean duplicate,
    boolean turnCompleted,
    boolean extractionCompleted,
    Boolean shouldRemember,
    Double importance,
    Double confidence,
    boolean ltmAttempted,
    boolean ltmPersisted,
    int entityRowsCreatedOrUpdated,
    int relationRowsCreatedOrUpdated,
    int evidenceRowsCreated,
    boolean turnIngestRecorded,
    int ltmRowsBefore,
    int ltmRowsAfter,
    boolean pruneOccurred,
    int pruneDeletedEstimate,
    RowMapping rows,
    List<EntityRow> entities,
    List<RelationRow> relations,
    String errorStage,
    String errorType,
    String errorMessage
) {
    public record RowMapping(
        List<String> longTermMemoryIds,
        List<String> entityIds,
        List<String> relationIds,
        List<String> evidenceIds
    ) {}

    public record EntityRow(
        String id,
        String normalizedName,
        String displayName,
        String entityType
    ) {}

    public record RelationRow(
        String id,
        String sourceEntityId,
        String sourceName,
        String sourceType,
        String targetEntityId,
        String targetName,
        String targetType,
        String predicate
    ) {}

    public record TableSnapshot(long count, List<Map<String, Object>> rows) {}

    public record Snapshot(
        String status,
        String databasePath,
        String userId,
        String model,
        String promptSha256,
        boolean evaluationEnabled,
        Map<String, TableSnapshot> tables
    ) {}

    public record ResetResult(
        String status,
        String databasePath,
        String userId,
        Map<String, Integer> deleted,
        Snapshot after
    ) {}

    public record Failure(
        String status,
        String sampleId,
        String errorStage,
        String errorType,
        String errorMessage
    ) {}
}
