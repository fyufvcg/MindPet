package model;

/** Explicit wire names for the isolated retrieval evaluation API. */
public enum RetrievalMode {
    KEYWORD_ONLY("keyword_only"),
    VECTOR_ONLY("vector_only"),
    RRF("rrf"),
    FULL("mindpet_full"),
    FULL_RRF_NORM("mindpet_full_rrf_norm"),
    RRF_NORM_ONLY("mindpet_rrf_norm_only"),
    RRF_NORM_TIME("mindpet_rrf_norm_time"),
    RRF_NORM_IMPORTANCE("mindpet_rrf_norm_importance"),
    RRF_NORM_IMPORTANCE_BONUS("mindpet_rrf_norm_importance_bonus");

    private final String wireName;

    RetrievalMode(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static RetrievalMode fromWireName(String value) {
        for (RetrievalMode mode : values()) {
            if (mode.wireName.equals(value)) return mode;
        }
        throw new IllegalArgumentException("Unsupported retrieval mode");
    }
}
