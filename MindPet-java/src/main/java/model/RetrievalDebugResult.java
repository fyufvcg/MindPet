package model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Evaluation-only response. Missing scores stay null, including with NON_NULL defaults. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RetrievalDebugResult(
    String status, String mode, int topK, List<Entry> results
) {
    public RetrievalDebugResult {
        results = List.copyOf(results);
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Entry(
        String memoryId,
        String content,
        Integer vectorRank,
        Integer keywordRank,
        Double keywordScore,
        Double distance,
        Double rrfScore,
        Double rrfNormalized,
        double importance,
        double confidence,
        int layer,
        String emotion,
        String createdAt,
        Double timeScore,
        Double importanceContribution,
        Double confidenceContribution,
        Double highImportanceBonus,
        Double finalScore
    ) {}

    public record Failure(String status, String code, String message) {}
}
