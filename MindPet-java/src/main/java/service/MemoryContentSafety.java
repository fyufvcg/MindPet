package service;

import java.util.Locale;
import java.util.regex.Pattern;

/** Deterministic guard for secrets and sensitive identifiers in curated memory. */
public final class MemoryContentSafety {

    private static final Pattern SECRET_LABEL = Pattern.compile(
        "(?i)(api[\\s_-]*key|access[\\s_-]*token|refresh[\\s_-]*token|password|passwd|cookie|验证码|短信码|密码|密钥|访问令牌|身份证|银行卡|卡号)"
    );
    private static final Pattern SECRET_TOKEN = Pattern.compile(
        "(?i)(?:\\bsk-[a-z0-9_-]{12,}\\b|\\bbearer\\s+[a-z0-9._-]{12,}\\b)"
    );
    private static final Pattern NATIONAL_ID = Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\d)");
    private static final Pattern LONG_CARD_NUMBER = Pattern.compile("(?<!\\d)(?:\\d[ -]?){15,18}\\d(?!\\d)");
    private static final Pattern NEGATION_BEFORE_VALUE = Pattern.compile(
        "(?iu)(?:并非|没有|从未|不曾|不再|不是|不|没|未|无|\\b(?:not|never|no longer|do not|don't|does not|doesn't|did not|didn't|is not|isn't|was not|wasn't|cannot|can't)\\b)"
            + "[^，。！？；,.!?;]{0,18}$"
    );
    private static final Pattern NEGATION_AFTER_VALUE = Pattern.compile(
        "(?iu)^[^，。！？；,.!?;]{0,18}(?:并非|不是|不再是|不再|不是|\\b(?:is not|isn't|was not|wasn't|no longer)\\b)"
    );
    private static final Pattern UNCERTAINTY_BEFORE_VALUE = Pattern.compile(
        "(?iu)(?:可能|也许|或许|不一定|不确定|好像|似乎|大概|未必|\\b(?:maybe|perhaps|probably|might|may|unsure|uncertain)\\b)"
            + "[^，。！？；,.!?;]{0,18}$"
    );
    private static final Pattern UNCERTAINTY_AFTER_VALUE = Pattern.compile(
        "(?iu)^[^，。！？；,.!?;]{0,18}(?:可能|也许|或许|不一定|不确定|好像|似乎|大概|未必|\\b(?:maybe|perhaps|probably|might|may|unsure|uncertain)\\b)"
    );

    private MemoryContentSafety() {}

    public static boolean looksSensitive(String value) {
        if (value == null || value.isBlank()) return false;
        String normalized = value.toLowerCase(Locale.ROOT);
        return SECRET_LABEL.matcher(normalized).find()
            || SECRET_TOKEN.matcher(normalized).find()
            || NATIONAL_ID.matcher(normalized).find()
            || LONG_CARD_NUMBER.matcher(normalized).find();
    }

    /** Collapse whitespace for exact evidence matching without changing wording. */
    public static String normalizeEvidence(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n\\t\\u00a0]+", " ")
            .replaceAll("\\s+", " ").trim();
    }

    public static boolean supportsExactEvidence(String source, String evidence) {
        String normalizedSource = normalizeEvidence(source);
        String normalizedEvidence = normalizeEvidence(evidence);
        return !normalizedEvidence.isBlank() && normalizedSource.contains(normalizedEvidence);
    }

    /**
     * Refuse affirmative or certain facts when the cited wording makes the value's
     * polarity negative or explicitly uncertain. Negative facts remain non-projectable.
     */
    public static boolean polarityConsistent(String evidence, String value, String assertion) {
        String text = normalizeEvidence(evidence);
        String normalizedValue = normalizeEvidence(value);
        if (text.isBlank() || normalizedValue.isBlank()) return false;
        String normalizedAssertion = assertion == null ? "" : assertion.trim().toLowerCase(Locale.ROOT);
        boolean negativeAssertion = "negated".equals(normalizedAssertion);
        boolean uncertainAssertion = "possible".equals(normalizedAssertion)
            || "uncertain".equals(normalizedAssertion);

        int from = 0;
        while ((from = text.indexOf(normalizedValue, from)) >= 0) {
            int end = from + normalizedValue.length();
            String before = clauseBefore(text, from);
            String after = clauseAfter(text, end);
            if (!negativeAssertion && !uncertainAssertion && (NEGATION_BEFORE_VALUE.matcher(before).find()
                    || NEGATION_AFTER_VALUE.matcher(after).find())) return false;
            if (!uncertainAssertion && (UNCERTAINTY_BEFORE_VALUE.matcher(before).find()
                    || UNCERTAINTY_AFTER_VALUE.matcher(after).find())) return false;
            from = end;
        }
        return true;
    }

    private static String clauseBefore(String text, int valueStart) {
        int start = Math.max(0, valueStart - 24);
        for (int i = start; i < valueStart; i++) {
            if (isClauseBoundary(text.charAt(i))) start = i + 1;
        }
        String clause = text.substring(start, valueStart);
        int contrast = Math.max(Math.max(clause.lastIndexOf("但是"), clause.lastIndexOf("不过")),
            Math.max(clause.lastIndexOf("但"), clause.lastIndexOf("而是")));
        return contrast < 0 ? clause : clause.substring(contrast + (clause.startsWith("但是", contrast) || clause.startsWith("不过", contrast) || clause.startsWith("而是", contrast) ? 2 : 1));
    }

    private static String clauseAfter(String text, int valueEnd) {
        int end = Math.min(text.length(), valueEnd + 24);
        for (int i = valueEnd; i < end; i++) {
            if (isClauseBoundary(text.charAt(i))) {
                end = i;
                break;
            }
        }
        return text.substring(valueEnd, end);
    }

    private static boolean isClauseBoundary(char value) {
        return "，。！？；,.!?;".indexOf(value) >= 0;
    }
}
