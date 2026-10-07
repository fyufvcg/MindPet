package service.v3;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure, deterministic entity promotion, canonicalization and turn-local deduplication. */
public final class V3EntityCanonicalizer {

    private static final Pattern PREFERENCE_WRAPPER = Pattern.compile(
        "^.*(?:选购|表达|回答|阅读|沟通)?偏好[（(]([^）)]+)[）)]$");
    private static final Pattern SEQUENCE_PREFERENCE = Pattern.compile(
        "^先(?:看|给|说|提供|展示)?(.+?)[，,、;；\\s]*(?:再|后)(?:看|给|补充|说明|提供|展示)?(.+)$");
    private static final Pattern LEADING_GOAL_WINDOW = Pattern.compile(
        "^(?:未来|接下来)?[0-9零一二三四五六七八九十两]+(?:个)?(?:年|月|周|天)(?:内|之内)");
    private static final Pattern FUTURE_GOAL_WINDOW = Pattern.compile(
        "^((?:未来|接下来)[0-9零一二三四五六七八九十两]+(?:个)?(?:年|月|周|天))(?:内|之内)");
    private static final Pattern PRACTICE_GOAL = Pattern.compile(
        "^(?:每天|每日|每周)?练习(?:[0-9零一二三四五六七八九十两]+(?:分钟|小时))?(.+)$");
    private static final Pattern RELATIVE_EVENT_CLOCK = Pattern.compile(
        "^(今晚|今早|今天|明晚|明天|后天)[0-9零一二三四五六七八九十两]+点(?:半|[0-9零一二三四五六七八九十两]+分)?");
    private static final Pattern ATTRIBUTE_PRIORITY = Pattern.compile(
        "^.*?((?:性能|质量|价格|重量|续航|便携性?|隐私|安全|可靠性|速度|准确性|简洁性?)优先)$");

    private V3EntityCanonicalizer() {}

    public record Input(
        String mention,
        String canonicalName,
        String type,
        String summary,
        double importance,
        List<String> aliases,
        boolean promote
    ) {}

    public record Entity(
        String canonicalName,
        String type,
        String summary,
        double importance,
        List<String> aliases
    ) {}

    public static List<Entity> canonicalize(List<Input> inputs, int limit) {
        Map<String, Entity> unique = new LinkedHashMap<>();
        if (inputs == null) return List.of();
        for (Input input : inputs) {
            if (input == null || !input.promote()) continue;
            String mention = clean(input.mention());
            String type = clean(input.type()).toLowerCase(Locale.ROOT);
            if (type.isBlank()) type = "other";
            String requestedCanonical = clean(input.canonicalName());
            if (requestedCanonical.isBlank()) requestedCanonical = mention;
            if ("goal".equals(type)
                    && (mention.startsWith("未来") || mention.startsWith("接下来"))
                    && !(requestedCanonical.startsWith("未来") || requestedCanonical.startsWith("接下来"))) {
                requestedCanonical = mention;
            }
            String canonical = canonicalName(requestedCanonical, type);
            if (canonical.isBlank()) continue;

            LinkedHashSet<String> aliases = new LinkedHashSet<>();
            addAlias(aliases, mention, canonical);
            addAlias(aliases, requestedCanonical, canonical);
            if (input.aliases() != null) {
                for (String alias : input.aliases()) addAlias(aliases, alias, canonical);
            }
            Entity next = new Entity(
                canonical, type, clean(input.summary()), clamp(input.importance()), List.copyOf(aliases));
            String key = key(canonical) + "\u0000" + type;
            Entity previous = unique.get(key);
            if (previous == null) {
                unique.put(key, next);
            } else {
                LinkedHashSet<String> mergedAliases = new LinkedHashSet<>(previous.aliases());
                mergedAliases.addAll(next.aliases());
                Entity preferred = next.importance() > previous.importance() ? next : previous;
                String summary = preferred.summary().isBlank()
                    ? (preferred == next ? previous.summary() : next.summary()) : preferred.summary();
                unique.put(key, new Entity(
                    previous.canonicalName(), previous.type(), summary,
                    Math.max(previous.importance(), next.importance()), List.copyOf(mergedAliases)));
            }
            if (unique.size() >= Math.max(1, limit)) break;
        }
        return new ArrayList<>(unique.values());
    }

    /**
     * Removes presentation syntax from descriptive entities while retaining the semantic identity.
     * Proper names and ordinary topic/tool/technology entities are intentionally left untouched.
     */
    public static String canonicalName(String value, String type) {
        String result = clean(value);
        if (result.isBlank()) return result;
        if ("preference".equals(type)) {
            Matcher wrapper = PREFERENCE_WRAPPER.matcher(result);
            if (wrapper.matches()) result = clean(wrapper.group(1));
            Matcher sequence = SEQUENCE_PREFERENCE.matcher(result);
            if (sequence.matches()) {
                result = "先" + clean(sequence.group(1)) + "后" + clean(sequence.group(2));
            }
            result = result.replaceFirst("(?:的)?(?:回答|回复|表达|沟通|阅读)(?:方式|风格)$", "");
            result = result.replaceFirst("偏好$", "");
            Matcher priority = ATTRIBUTE_PRIORITY.matcher(result);
            if (priority.matches()) result = clean(priority.group(1));
            if (result.matches("^(?:性能|质量|价格|重量|续航|便携性?|隐私|安全|可靠性|速度|准确性|简洁性?)$")) {
                result += "优先";
            }
        } else if ("goal".equals(type)) {
            Matcher futureWindow = FUTURE_GOAL_WINDOW.matcher(result);
            if (futureWindow.find()) {
                result = futureWindow.replaceFirst("$1");
            } else {
                result = LEADING_GOAL_WINDOW.matcher(result).replaceFirst("");
            }
            result = result.replaceAll("一(?:篇|个|项|份|本|次)(?=[\\p{IsHan}A-Za-z0-9])", "");
            Matcher practice = PRACTICE_GOAL.matcher(result);
            if (practice.matches()) result = clean(practice.group(1)) + "练习";
            if (result.matches("^考到(?:驾照|执照|.*(?:资格证|证书))$")) {
                result = result.replaceFirst("^考到", "考取");
            }
        } else if ("event".equals(type)) {
            result = result.replaceFirst("^(每周[一二三四五六日天])(?:的)?(?:早上|上午|中午|下午|晚上|夜里)", "$1");
            result = result.replaceFirst("^(?:未来|接下来)[0-9零一二三四五六七八九十两]+(?:个)?(?:天|周|月)(?:内)?(?:每天|每日)?", "");
            result = RELATIVE_EVENT_CLOCK.matcher(result).replaceFirst("$1");
            result = result.replaceFirst("^(今晚|今早|今天|明晚|明天|后天)去", "$1");
            result = result.replaceAll("一(?:场|次)(?=[\\p{IsHan}A-Za-z0-9])", "");
        }
        return clean(result);
    }

    public static String key(String value) {
        return clean(value).toLowerCase(Locale.ROOT);
    }

    public static String clean(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replaceAll("[\\r\\n\\t]+", " ")
            .replaceAll("\\s+", " ")
            .trim();
        return normalized.length() <= 256 ? normalized : normalized.substring(0, 256);
    }

    private static void addAlias(LinkedHashSet<String> aliases, String value, String canonical) {
        String cleaned = clean(value);
        if (!cleaned.isBlank() && !key(cleaned).equals(key(canonical))) aliases.add(cleaned);
    }

    private static double clamp(double value) {
        if (!Double.isFinite(value)) return 0.5;
        return Math.max(0.0, Math.min(1.0, value));
    }
}
