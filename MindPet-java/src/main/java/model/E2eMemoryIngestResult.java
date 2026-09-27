package model;

import java.util.List;
import java.util.Map;

/** Evaluation-only diagnostics for one real completed-turn memory ingestion. */
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
    Boolean worthRemembering,
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
    ParseInfo parse,
    String errorStage,
    String errorType,
    String message
) {
    public record RowMapping(
        List<String> longTermMemoryIds,
        List<String> entityIds,
        List<String> relationIds,
        List<String> evidenceIds
    ) {}

    public record ParseInfo(
        boolean memoryObjectPresent,
        boolean importanceFallbackUsed,
        boolean confidenceFallbackUsed,
        boolean importanceClamped,
        boolean confidenceClamped
    ) {}

    public record TableCount(long total, long evalUser) {}

    public record Snapshot(
        String status,
        String database,
        String userId,
        Map<String, TableCount> tables
    ) {}

    public record ResetResult(
        String status,
        String database,
        String userId,
        Map<String, Integer> deleted,
        Snapshot after
    ) {}

    public record Failure(
        String status,
        String sampleId,
        String errorStage,
        String errorType,
        String message
    ) {}
}
