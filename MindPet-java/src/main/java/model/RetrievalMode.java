package model;

/** Explicit wire names for the isolated retrieval evaluation API. */
public enum RetrievalMode {
    KEYWORD_ONLY("keyword_only"),
    VECTOR_ONLY("vector_only"),
    RRF("rrf"),
    FULL("mindpet_full");

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
