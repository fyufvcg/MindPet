package service;

/** Frozen public tokenizer for comparable corpus and context budgets, not a provider usage estimate. */
public final class MemoryTokenCounter {
    public static final String ENCODING = "o200k_base";
    private static final com.knuddels.jtokkit.api.Encoding TOKENIZER =
        com.knuddels.jtokkit.Encodings.newDefaultEncodingRegistry()
            .getEncoding(com.knuddels.jtokkit.api.EncodingType.O200K_BASE);
    private MemoryTokenCounter() {}
    public static int count(String value) { return TOKENIZER.countTokensOrdinary(value == null ? "" : value); }
}
