package service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Conservative source retirement. Absence of recent access is not evidence of invalidity. */
public final class MemoryForgettingPolicy {
    private MemoryForgettingPolicy() {}

    private static final String DATE = "(?:\\d{4}-\\d{1,2}-\\d{1,2}|\\d{4}年\\d{1,2}月\\d{1,2}日)";
    private static final Pattern RANGE = Pattern.compile(
        "(?:确认)?有效期(?:为|是|[:：])?\\s*(" + DATE + ")\\s*(?:至|到|~|～)\\s*(" + DATE + ")");
    private static final Pattern END = Pattern.compile(
        "(?:有效至|有效截止(?:日期)?(?:为|是|至|到|[:：])?|到期(?:日期|日)(?:为|是|[:：])?|"
            + "(?i:expires\\s+on)\\s*)\\s*(" + DATE + ")");
    private static final Pattern PROTECTED = Pattern.compile(
        "长期|一直|习惯|偏好|历史|回顾|复盘|曾经|以前|取消|不再|已否定|放弃");
    private static final Pattern MIXED = Pattern.compile("另外|此外|同时|除此之外|\\n");

    public record Decision(boolean retire, String reason) {}

    public static Decision decide(String content, double importance, double confidence, int accessCount,
                                  int layer, Timestamp accessed, Timestamp created, MemoryRetrievalPolicy time) {
        if (content == null || content.isBlank()) return new Decision(false, "missing_source");
        if (MIXED.matcher(content).find())
            return new Decision(false, "durable_history_or_mixed_source");
        // A whole source containing stable information, completed history, or a version
        // transition must not be archived because one interval mentioned in it ended.
        if(Pattern.compile("独立|不受.*期限|和.*无关|另记|另有|较早|此前|随后|之后改|此后改|已完成|回看|当时|历史|曾允许")
                .matcher(content).find())
            return new Decision(false,"durable_history_or_mixed_source");
        Instant naturalEnd=MemorySourceValidity.explicitEnd(content);
        if(naturalEnd!=null && !PROTECTED.matcher(content).find()) {
            return naturalEnd.toEpochMilli()<=time.nowMillis()
                ?new Decision(true,"explicit_validity_expired"):new Decision(false,"explicit_validity_current");
        }
        List<Instant> deadlines = new ArrayList<>();
        int validityStart = content.length();
        var ranges = RANGE.matcher(content);
        boolean ambiguous = false;
        while (ranges.find()) {
            validityStart = Math.min(validityStart, ranges.start());
            Instant start = date(ranges.group(1)), end = date(ranges.group(2));
            if (start == null || end == null || !end.isAfter(start)) {
                ambiguous = true;
                continue;
            }
            // A whole row cannot be retired because one of several independent sentences expired.
            long assertions = Pattern.compile("[。！？!?]").matcher(content.substring(0, ranges.start()))
                .results().count();
            if (assertions > 1) ambiguous = true;
            if (!validitySuffix(content.substring(ranges.end()))) ambiguous = true;
            deadlines.add(end);
        }
        var ends = END.matcher(content);
        while (ends.find()) {
            validityStart = Math.min(validityStart, ends.start());
            Instant end = date(ends.group(1));
            if (end == null) ambiguous = true;
            else deadlines.add(end);
            if (Pattern.compile("[。！？!?]").matcher(content.substring(0, ends.start())).results().count() > 1)
                ambiguous = true;
            if (!validitySuffix(content.substring(ends.end()))) ambiguous = true;
        }
        if (ambiguous) return new Decision(false, "ambiguous_validity");
        if (!deadlines.isEmpty()) {
            if (deadlines.stream().distinct().count() > 1) return new Decision(false, "multiple_validity_scopes");
            // A bounded arrangement may contain a project title such as "复盘" or the word
            // "习惯". Those words alone do not override its explicit validity. Actual history,
            // cancellation, and durable preferences still protect the assertion before the date.
            String assertion = content.substring(0, validityStart);
            if (Pattern.compile("长期|一直|偏好|历史|回顾|曾经|以前|取消|不再|已否定|放弃|(?:用于|供|用来)复盘")
                    .matcher(assertion).find())
                return new Decision(false, "durable_history_or_mixed_source");
            return deadlines.get(0).toEpochMilli() <= time.nowMillis()
                ? new Decision(true, "explicit_validity_expired") : new Decision(false, "explicit_validity_current");
        }
        if (PROTECTED.matcher(content).find())
            return new Decision(false, "durable_history_or_mixed_source");
        // Age and low access do not invalidate an affirmative statement. Only explicitly
        // provisional suggestions are eligible for confidence decay; durable sources need expiry.
        if (!Pattern.compile("尚未确认|尚待确认|未确认|待核实|未经核实|有人建议|候选建议|可能|猜测|传闻|暂定")
                .matcher(content).find())
            return new Decision(false, "affirmative_source_without_invalidity");
        // Screened high-confidence evidence remains available unless there is explicit invalidity.
        if (!Double.isFinite(confidence) || confidence >= 0.7 || importance >= 0.6 || accessCount >= 3)
            return new Decision(false, "confirmed_or_reinforced_without_expiry");
        if (!Double.isFinite(importance) || accessed == null && created == null)
            return new Decision(false, "missing_decay_evidence");
        return time.rawRetention(accessed, created, importance, layer) < time.retentionMinimum()
            ? new Decision(true, "low_confidence_unreinforced_decay") : new Decision(false, "recent_uncertain_source");
    }

    private static boolean validitySuffix(String suffix) {
        String normalized = suffix.replaceAll("[\\p{P}\\p{Z}\\s]+", "");
        return normalized.isEmpty() || normalized.matches(
            "(?:到期后(?:需要重新确认(?:不能继续当作当前安排)?|失效|不再有效))?"
                + "(?:活动日期为\\d{4}\\d{1,2}\\d{1,2})?");
    }

    private static Instant date(String value) {
        try {
            String[] parts = value.replace('年', '-').replace('月', '-').replace("日", "").split("-");
            return LocalDate.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]))
                .atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (RuntimeException invalid) { return null; }
    }
}
