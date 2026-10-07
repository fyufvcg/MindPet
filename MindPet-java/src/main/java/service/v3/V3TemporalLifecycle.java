package service.v3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Evidence-constrained event bounds: current state and future transition are distinct. */
public final class V3TemporalLifecycle {
    private static final Pattern NON_ASSERTED = Pattern.compile(
        "假设|假如|如果|可能|也许|或许|考虑|不确定|还没决定|他说|她说|据说|[？?]"
            + "|\\b(?:if|suppose|hypothetical|maybe|might|considering|undecided|he said|she said)\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern CURRENT = Pattern.compile(
        "现在|目前|最近|这段时间|这几天|暂时|临时|先|这周|今天|期间|这(?:个)?季度"
            + "|这(?:[一二两三四五六七八九十\\d]+|几)(?:天|周|个月)"
            + "|\\b(?:now|currently|recently|for now|temporarily|this week|today)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern END = Pattern.compile(
        "^(.*?)[,，]?\\s*(?:等|直到)(.+?)(?:以后|之后|后|了)[,，]?\\s*(.+)$");
    private static final Pattern EVENT_END = Pattern.compile(
        "^(.*)[,，]\\s*([^,，]*?(?:结束|完成|修好|交付|拿到|收到|恢复))(?:以后|之后|后)[,，]?\\s*(.+)$");
    private static final Pattern EN_END = Pattern.compile(
        "^(.*?)\\s+(?:until|once|when|after)\\s+(.+?)(?:,|;|\\bthen\\b)\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRANSITION = Pattern.compile(
        "搬|换|切回|改用|改为|回到|回去|归还|还给|交接|转到|转去|不再|停用"
            + "|\\b(?:move|switch|return|resume|stop|hand over|change)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern STATE = Pattern.compile(
        "(居住在?|住在?|使用|用|负责|参与|从事)\\s*([^，,。;；!?！？\\n]{1,100})$");
    private static final Pattern EN_STATE = Pattern.compile(
        "\\b(stay(?:ing)? at|liv(?:e|ing) (?:at|in)|us(?:e|ing)|work(?:ing)? on)\\s+(.{1,100})$", Pattern.CASE_INSENSITIVE);

    private V3TemporalLifecycle() {}

    public record Bound(String currentEvidence, String endCondition, String futureEvidence,
                        String currentEndpoint, String endpointType, String semanticPredicate,
                        String normalizedPredicate) {}

    public static List<Bound> bounds(String message) {
        List<Bound> result = new ArrayList<>();
        if (message == null) return result;
        for (String sentence : message.split("[。！!;；\\n]+")) {
            if (NON_ASSERTED.matcher(sentence).find()) continue;
            Matcher end = END.matcher(sentence.trim());
            if (!end.matches()) end = EVENT_END.matcher(sentence.trim());
            if (!end.matches()) end = EN_END.matcher(sentence.trim());
            if (!end.matches()) continue;
            String condition = end.group(2).trim(), future = end.group(3).trim();
            if (!TRANSITION.matcher(future).find()) continue;
            String current = "";
            for (String clause : end.group(1).split("[,，]")) {
                if (CURRENT.matcher(clause).find() && (STATE.matcher(clause.trim()).find()
                        || EN_STATE.matcher(clause.trim()).find())) current = clause.trim();
            }
            if (current.isBlank()) continue;
            Matcher state = STATE.matcher(current);
            if (!state.find()) state = EN_STATE.matcher(current);
            if (!state.find(0)) continue;
            String prefix = current.substring(0, state.start());
            // An implicit first-person present-state sentence is allowed; a named third party is not.
            if (!prefix.contains("我") && !prefix.toLowerCase(Locale.ROOT).matches(".*\\bi\\b.*")
                    && !CURRENT.matcher(prefix).replaceAll("").replaceAll("[\\s,，]", "").isBlank()) continue;
            String verb = state.group(1).toLowerCase(Locale.ROOT);
            String object = state.group(2).trim().replaceAll("[。,.，]+$", "")
                .replaceFirst("(?:[一二两三四五六七八九十\\d]+|几)(?:天|周|个月)$", "").trim();
            boolean residence = verb.contains("住") || verb.startsWith("stay") || verb.startsWith("liv");
            boolean tool = verb.equals("用") || verb.equals("使用") || verb.startsWith("us");
            result.add(new Bound(current, condition, future, object,
                residence ? "place" : tool ? "tool" : "project",
                residence ? "TEMPORARILY_LIVES_AT" : tool ? "TEMPORARILY_USES" : "TEMPORARILY_WORKS_ON",
                residence ? "related_to" : tool ? "uses" : "works_on"));
        }
        return result;
    }

    public static boolean literalEndpoint(Bound bound) {
        String target = bound.currentEndpoint();
        return target.length() <= 64 && !target.matches(".*(?:写|做|开发|用于|进行|处理|来完成|\\bto\\b|\\bfor\\b).*" )
            && !target.matches("(?:它|这个|那个|这里|那里|那边|项目|工具|it|this|that)");
    }

    public static String classify(Bound bound, String source, String target, List<String> aliases,
                                  String normalizedPredicate, String semanticPredicate, String existingStatus) {
        if (!source.equalsIgnoreCase("user")) return existingStatus;
        String semantic = semanticPredicate.toUpperCase(Locale.ROOT);
        if (semantic.matches(".*(?:NOT|NO_LONGER|STOPPED|NEVER).*")) return existingStatus;
        List<String> surfaces = new ArrayList<>(aliases);
        surfaces.add(target);
        // A reified goal is still a known endpoint, not a new invented destination.
        surfaces.add(target.replaceFirst("(?i)^(?:搬到|搬去|搬往|迁往|move to)\\s*", ""));
        boolean inCurrent = surfaces.stream().anyMatch(s -> !key(s).isBlank()
            && key(bound.currentEndpoint()).contains(key(s)));
        if (inCurrent && normalizedPredicate.equals(bound.normalizedPredicate())) return "BOUNDED";
        boolean inFuture = surfaces.stream().anyMatch(s -> !key(s).isBlank()
            && key(bound.endCondition()+" "+bound.futureEvidence()).contains(key(s)));
        if (inFuture && (normalizedPredicate.equals("plans")
                || normalizedPredicate.equals(bound.normalizedPredicate()))) return "FUTURE";
        return existingStatus;
    }

    private static String key(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
