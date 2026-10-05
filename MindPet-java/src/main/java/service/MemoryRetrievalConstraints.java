package service;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Explicit item identifiers take precedence over broad topic and tense vocabulary. */
public final class MemoryRetrievalConstraints {
    private MemoryRetrievalConstraints() {}
    private static final Pattern ORDINAL = Pattern.compile("第([0-9一二三四五六七八九十]+)(?:和第([0-9一二三四五六七八九十]+))?(环节|步骤|阶段|项|条|版)");
    private static final Pattern NUMBERED = Pattern.compile("(许可|安排|方案|任务|作业)([0-9]+)");
    private record Anchor(String noun, int number) {}

    private static Set<Anchor> anchors(String text) {
        Set<Anchor> result = new LinkedHashSet<>();
        text = text.replaceAll("第([0-9一二三四五六七八九十]+)[、,，]第([0-9一二三四五六七八九十]+)(环节|步骤|阶段|项|条|版)","第$1$3和第$2$3");
        var ordinal = ORDINAL.matcher(text);
        while (ordinal.find()) {
            result.add(new Anchor(ordinal.group(3), number(ordinal.group(1))));
            if (ordinal.group(2) != null) result.add(new Anchor(ordinal.group(3), number(ordinal.group(2))));
        }
        var numbered = NUMBERED.matcher(text);
        while (numbered.find()) result.add(new Anchor(numbered.group(1), number(numbered.group(2))));
        if (text.contains("活动")) {
            result = result.stream().map(a -> "项".equals(a.noun()) ? new Anchor("活动", a.number()) : a)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        }
        if (text.contains("临时")) {
            result = result.stream().map(a -> Set.of("项", "安排").contains(a.noun())
                ? new Anchor("临时安排", a.number()) : a)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (result.isEmpty()) {
                if (text.contains("另一项")) result.add(new Anchor("临时安排", 2));
                else if (text.contains("一项")) result.add(new Anchor("临时安排", 1));
            }
        }
        return result;
    }
    private static int number(String text) {
        try { return Integer.parseInt(text); }
        catch (NumberFormatException ignored) {
            String digits = "零一二三四五六七八九";
            if (text.length() == 1) return text.equals("十") ? 10 : digits.indexOf(text);
            if (text.matches("[一二三四五六七八九]?十[一二三四五六七八九]?")) {
                int ten = text.indexOf('十');
                return (ten == 0 ? 1 : digits.indexOf(text.substring(0, ten))) * 10
                    + (ten == text.length() - 1 ? 0 : digits.indexOf(text.substring(ten + 1)));
            }
            return -1;
        }
    }
    public static double priority(String query, String content) {
        String q = MemoryRetrievalRanking.normalize(query), text = MemoryRetrievalRanking.normalize(content);
        Set<Anchor> requested = anchors(q), stored = anchors(text);
        if (requested.isEmpty()) {
            if (q.contains("另一项") && text.contains("另一项")) return 2;
            if (q.contains("取消") && cancellation(text)) return 1;
            return 0;
        }
        long matches = requested.stream().filter(stored::contains).count();
        // A numbered work item may be expressed as "one cancellable job" / "another job".
        // Match that explicit discourse marker; never infer a number from database order.
        if (matches == 0 && (q.contains("作业") || q.contains("取消之前"))
                && (text.contains("可取消") || text.contains("作业")
                    || q.contains("取消之前") && (text.contains("安排")
                        || text.matches(".*确认(?:了)?(?:另)?一项.*")))) {
            for (Anchor a : requested) if (Set.of("项", "安排", "作业").contains(a.noun())) {
                if (a.number() == 1 && text.contains("一项") && !text.contains("另一项")
                        || a.number() == 2 && text.contains("另一项")) matches++;
            }
        }
        if (matches == 0) {
            if (q.contains("取消") && cancellation(text)) return 1;
            return 0;
        }
        double priority = 2.0 * matches / requested.size();
        if (q.contains("作业") && cancellation(text)) priority += 0.3;
        return priority;
    }
    private static boolean cancellation(String text) {
        return text.contains("取消") && !text.contains("可取消") || text.contains("不再有效");
    }
    public static boolean focused(String query) {
        String q=MemoryRetrievalRanking.normalize(query);
        // An ordinal attached to a completed activity must not hide unnumbered
        // activity sources simply because an unrelated job has the same ordinal.
        if (q.contains("活动") && MemoryQueryIntent.historical(query)) return false;
        // A compound request may also need independent, unnumbered evidence. In that
        // case identifiers improve ranking but must not filter the other requested facts.
        if (Pattern.compile("(?:另外|此外|同时|以及|还有|并且|和|与).*(?:偏好|习惯|工具|负责人|通知|阅读|集合|标记)")
                .matcher(q).find()) return false;
        return !anchors(q).isEmpty() || q.contains("另一项");
    }
}
