package model;

import java.util.List;

/** Evaluation-only extraction snapshot and deterministic replay contract. */
public final class FormalEvaluationContract {
    private FormalEvaluationContract() {}

    public enum Variant {
        A0_DIRECT_SAVE_ALL,
        A1_LLM_DECISION_ONLY,
        B0_CURRENT_FULL,
        B1_NO_PREDICATE_WHITELIST,
        B2_NO_LTM_CONFIDENCE_GATE
    }

    public record EntityCandidate(
        String name,
        String type,
        String summary,
        double importance
    ) {}

    public record RelationCandidate(
        String source,
        String target,
        String predicate,
        double confidence,
        double importance,
        boolean predicateAllowed,
        boolean confidenceGatePassed
    ) {}

    public record ExtractionSnapshot(
        String schemaVersion,
        String promptSha256,
        String model,
        String sourceSha256,
        boolean rawWorthRemembering,
        Boolean rawMemoryShouldRemember,
        boolean combinedShouldRemember,
        double importance,
        double confidence,
        String evidence,
        List<EntityCandidate> entities,
        List<RelationCandidate> relations,
        EvaluationWriteTrace.ParseDiagnostics parse,
        EvaluationWriteTrace.KgFilterTrace kgFilter,
        EvaluationWriteTrace.TemporalTrace temporal,
        String snapshotSha256
    ) {}

    public record ExtractionRequest(
        String sampleId,
        String runId,
        String userMessage,
        String assistantContext,
        String emotion,
        String occurredAt
    ) {}

    public record ExtractionResponse(
        String status,
        String sampleId,
        String runId,
        String userId,
        ExtractionSnapshot snapshot
    ) {}

    public record ReplayRequest(
        String sampleId,
        String runId,
        String userMessage,
        String assistantContext,
        String emotion,
        String occurredAt,
        Variant variant,
        ExtractionSnapshot snapshot
    ) {}

    public record ReplayResponse(
        String status,
        String variant,
        String extractionSnapshotSha256,
        boolean llmCalled,
        E2eMemoryIngestResult result
    ) {}
}
