package model;

/** Debug-safe output from the evaluation-only production importance scorer. */
public record ImportanceScoreResult(
    String status,
    String model,
    boolean worthRemembering,
    boolean shouldRemember,
    double importance,
    double confidence,
    boolean highImportance,
    boolean wouldPersistMemory,
    ParseInfo parse
) {
    public record ParseInfo(
        boolean succeeded,
        boolean memoryObjectPresent,
        boolean importanceFallbackUsed,
        boolean confidenceFallbackUsed,
        boolean importanceClamped,
        boolean confidenceClamped
    ) {}

    public record Failure(String status, String code, String message) {}
}
