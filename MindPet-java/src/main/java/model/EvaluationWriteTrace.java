package model;

import java.util.List;

/** Evaluation-only observation of the production memory-write path. */
public record EvaluationWriteTrace(
    DecisionTrace decision,
    ParseDiagnostics parse,
    KgFilterTrace kgFilter,
    TemporalTrace temporal,
    ProvenanceTrace provenance
) {
    public record DecisionTrace(
        Boolean rawWorthRemembering,
        Boolean rawMemoryShouldRemember,
        Boolean combinedShouldRemember,
        Double importance,
        Double confidence,
        double importanceThreshold,
        double confidenceThreshold,
        Boolean importanceGatePassed,
        Boolean confidenceGatePassed,
        boolean ltmAttempted,
        boolean ltmPersisted,
        String ltmFailureReason
    ) {}

    public record ParseDiagnostics(
        Boolean memoryObjectPresent,
        Boolean importanceFallbackUsed,
        Boolean confidenceFallbackUsed,
        Boolean importanceClamped,
        Boolean confidenceClamped,
        boolean parseFailure,
        String parseFailureReason
    ) {}

    public record KgFilterTrace(
        Integer rawEntityCount,
        Integer rawRelationCount,
        Integer normalizedEntityCount,
        Integer normalizedRelationCount,
        Integer sensitivityRejectedEntityCount,
        Integer predicateWhitelistRejectedCount,
        Integer relationConfidenceRejectedCount,
        Integer persistedEntityCount,
        Integer persistedRelationCount,
        Integer evidenceCount
    ) {}

    public record TemporalTrace(
        String eventDate,
        String eventAt,
        String eventTimezone,
        String eventPrecision,
        String referenceTimestamp,
        String referenceTimezone
    ) {}

    public record ProvenanceTrace(
        String sampleId,
        String turnHash,
        String sessionId,
        String sourceUserMessageId,
        String sourceAssistantMessageId,
        List<String> kgEntityRowIds,
        List<String> kgRelationRowIds,
        List<String> kgEvidenceRowIds,
        List<String> longTermMemoryRowIds
    ) {}
}
