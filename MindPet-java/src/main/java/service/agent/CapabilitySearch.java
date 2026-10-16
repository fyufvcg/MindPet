package service.agent;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Local lexical discovery: terms and Chinese bigrams, without another model-routing call. */
public final class CapabilitySearch {
    private CapabilitySearch() {}
    public static int score(String query, String text) {
        String needle = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        if (needle.isBlank()) return 1;
        String haystack = text.toLowerCase(Locale.ROOT);
        int score = haystack.contains(needle) ? 100 : 0;
        Set<String> terms = new LinkedHashSet<>();
        var matcher = Pattern.compile("[a-z0-9_]+|[\\p{IsHan}]+").matcher(needle);
        while (matcher.find()) {
            String term = matcher.group();
            terms.add(term);
            if (term.codePoints().allMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN))
                for (int i = 0; i + 1 < term.length(); i++) terms.add(term.substring(i, i + 2));
        }
        for (String term : terms) if (haystack.contains(term)) score += Math.min(12, term.length());
        return score;
    }
}
