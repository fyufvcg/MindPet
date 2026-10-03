package service;

import java.text.Normalizer;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Query relevance is primary; retention controls eligibility, not RRF ranks. */
public final class MemoryRetrievalRanking {
    private MemoryRetrievalRanking() {}
    private static final Pattern WORDS = Pattern.compile("[a-z0-9_]+|\\p{IsHan}+");
    private static final Pattern SPACE = Pattern.compile("\\s+");
    private static final Set<String> STOP = Set.of("什么", "哪个", "哪些", "怎么", "如何", "为什么", "为何",
        "请问", "告诉", "告诉我", "我的", "我们", "是否", "一下", "的是", "是什么", "多少", "哪里");
    public static String normalize(String text) {
        return SPACE.matcher(normalizedText(text)).replaceAll("");
    }
    private static String normalizedText(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT);
    }
    /** IDF uses only the current user's eligible corpus; repeated query grams count once. */
    public static LexicalQuery lexicalQuery(String query, Collection<String> contents) {
        Set<String> features = new LinkedHashSet<>();
        var words = WORDS.matcher(normalizedText(query));
        while (words.find()) {
            String word = words.group();
            if (asciiFeature(word) || word.length() == 1) features.add(word);
            else for (int length = 2; length <= 4; length++) {
                for (int i = 0; i + length <= word.length(); i++) {
                    String feature = word.substring(i, i + length);
                    if (!STOP.contains(feature)) features.add(feature);
                }
            }
        }
        Map<String, Double> weights = new LinkedHashMap<>();
        var documents = contents.stream().map(MemoryRetrievalRanking::normalizedText).toList();
        for (String feature : features) {
            long frequency = documents.stream().filter(content -> matchesNormalized(content, feature)).count();
            // Unseen question phrasing is not evidence against every stored document.
            // Keep all features for the standalone scorer, which has no corpus statistics.
            if (!documents.isEmpty() && frequency == 0) continue;
            double idf = Math.log(1 + (contents.size() + 0.5) / (frequency + 0.5));
            weights.put(feature, idf * Math.min(4, feature.length()));
        }
        return new LexicalQuery(normalize(query), weights);
    }
    public record LexicalQuery(String query, Map<String, Double> weights) {
        public LexicalQuery { weights = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(weights)); }
        public double score(String content) {
            if (query.isEmpty() || content == null || content.isBlank()) return 0;
            String text = normalizedText(content);
            if (SPACE.matcher(text).replaceAll("").contains(query) && (!asciiFeature(query) || matchesNormalized(text, query))) return 1;
            double total = 0, hits = 0;
            for (var entry : weights.entrySet()) {
                total += entry.getValue();
                if (matchesNormalized(text, entry.getKey())) hits += entry.getValue();
            }
            return total == 0 ? 0 : hits / total;
        }
    }
    private static boolean matchesNormalized(String text, String feature) {
        if (!asciiFeature(feature)) return text.contains(feature);
        for (int start = text.indexOf(feature); start >= 0; start = text.indexOf(feature, start + 1)) {
            int end = start + feature.length();
            if ((start == 0 || !asciiWord(text.charAt(start - 1))) && (end == text.length() || !asciiWord(text.charAt(end)))) return true;
        }
        return false;
    }
    private static boolean asciiWord(char c) {
        return c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_';
    }
    private static boolean asciiFeature(String text) {
        if (text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) if (!asciiWord(text.charAt(i))) return false;
        return true;
    }
    public static double lexicalScore(String content, String query) {
        return lexicalQuery(query, java.util.List.of()).score(content);
    }
    public static boolean mentionsEntity(String text, String name) {
        String entity = normalize(name);
        if (entity.isEmpty()) return false;
        return asciiFeature(entity) ? matchesNormalized(normalizedText(text), entity) : normalize(text).contains(entity);
    }
    public static double rrf(int rank) { return rank > 0 ? 1.0 / (60.0 + rank) : 0; }
    public static double relevance(double lexical, double distance) {
        return Math.max(bounded(lexical), Double.isFinite(distance) ? bounded(1 - distance) : 0);
    }
    public static double retrievalScore(double rrf, int routes, double relevance, double preference) {
        return 0.65 * bounded(relevance) + 0.30 * bounded(rrf / (Math.max(1, routes) / 61.0))
            + 0.05 * bounded(preference);
    }
    public static double rerank(double rrf, double lexical, double distance,
                                double importance, double confidence, double ageHours, int layer) {
        double recency = Math.exp(-Math.max(0, ageHours) / (layer == 2 ? 121.0 : 25.0));
        return retrievalScore(rrf, 2, relevance(lexical, distance),
            0.5 * bounded(importance) + 0.3 * recency + 0.2 * bounded(confidence));
    }
    private static double bounded(double value) {
        return Double.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0;
    }
}
