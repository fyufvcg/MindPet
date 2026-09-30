package service;

import java.text.Normalizer;
import java.util.Locale;

/** Small, auditable equivalence rules. Entity, date and numeric values are never fuzzy matched. */
public final class MemoryValueNormalizer {
    private MemoryValueNormalizer() {}

    public static String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    public static String canonical(String predicate, String value) {
        String text = value == null ? "" : value.trim();
        if (!"preference".equals(predicate)) return text;
        return text.replace("先给结论", "先说结论").replace("先看结论", "先说结论")
            .replace("先给出结论", "先说结论").replace("先看到结论", "先说结论")
            .replace("再看简洁步骤", "再列简洁步骤").replace("再给简洁步骤", "再列简洁步骤");
    }

    public static boolean equivalent(String predicate, String left, String right) {
        return normalize(canonical(predicate, left)).equals(normalize(canonical(predicate, right)));
    }

    public static String findSurface(String predicate, String canonicalValue, String evidence) {
        if (evidence == null || canonicalValue == null || canonicalValue.isBlank()) return "";
        if (evidence.contains(canonicalValue)) return canonicalValue;
        if (!"preference".equals(predicate)) return "";
        String normalized = normalize(canonical(predicate, canonicalValue));
        for (int start = 0; start < evidence.length(); start++) {
            for (int end = start + 1; end <= Math.min(evidence.length(), start + canonicalValue.length() + 8); end++) {
                String candidate = evidence.substring(start, end);
                if (normalize(canonical(predicate, candidate)).equals(normalized)) return candidate;
            }
        }
        return "";
    }
}
