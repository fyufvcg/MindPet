package service.v3;

import java.util.Locale;
import java.util.Set;

/** Projects a rich semantic relation into the stable MindPet retrieval predicate contract. */
public final class V3PredicateProjector {

    public static final Set<String> NORMALIZED_PREDICATES = Set.of(
        "prefers", "dislikes", "uses", "learns", "builds", "works_on",
        "plans", "knows", "experienced", "belongs_to", "related_to");

    private V3PredicateProjector() {}

    public record Projection(String semanticPredicate, String normalizedPredicate, boolean projected) {}

    public static Projection project(String semanticPredicate, String requested, String temporalStatus) {
        Projection base = project(semanticPredicate, requested);
        if ("FUTURE".equalsIgnoreCase(temporalStatus) && base.semanticPredicate().matches(
                "(?:WILL_)?(?:MOVE|MOVES|MOVING|RELOCATE|RELOCATES|LIVE|LIVES|RESIDE|RESIDES)_(?:TO|IN|AT)(?:_.*)?")) {
            return new Projection(base.semanticPredicate(), "plans", !"plans".equals(requested));
        }
        return base;
    }

    public static Projection project(String semanticPredicate, String requestedNormalizedPredicate) {
        String semantic = normalizeSemantic(semanticPredicate);
        String requested = normalizePredicate(requestedNormalizedPredicate);
        if (semantic.isBlank()) {
            return new Projection(requested.toUpperCase(Locale.ROOT), requested, false);
        }
        String token = semantic.toLowerCase(Locale.ROOT);
        // Semantic roles take precedence over a shared verb such as WORKS.
        if (Set.of("WORKS_AS", "HAS_OCCUPATION", "HAS_PROFESSION", "STUDIED").contains(semantic)) {
            return new Projection(semantic, "experienced", !"experienced".equals(requested));
        }
        if (Set.of("WORKS_AT", "EMPLOYED_BY", "EMPLOYED_AT").contains(semantic)) {
            return new Projection(semantic, "belongs_to", !"belongs_to".equals(requested));
        }
        // The action head dominates its purpose/participant qualifiers. In particular USER is not USE,
        // and using a tool for study is still USES rather than LEARNS.
        if (token.matches("(?:uses?|drives?|rides?|commutes?)(?:_.*)?")) {
            return new Projection(semantic, "uses", !"uses".equals(requested));
        }
        String projected = containsAny(token,
                "dislike", "avoid", "hate", "allerg", "without", "不喜欢", "不加", "避免") ? "dislikes"
            : containsAny(token, "prefer", "favorite", "favour", "like", "偏好", "喜欢") ? "prefers"
            : containsAny(token, "曾用", "以前", "过去", "不再使用", "experience", "_past", "past_") ? "experienced"
            : containsAny(token, "plan", "intend", "goal", "target", "prepare", "next_", "future_",
                "every_", "计划", "打算", "准备", "目标", "未来", "每周", "固定进行") ? "plans"
            : containsAny(token, "learn", "study", "practice", "train", "学习", "练习", "训练") ? "learns"
            : token.matches(".*(?:^|_)(?:use|uses|used|using)(?:_|$).*" )
                || containsAny(token, "drive", "ride", "commute", "tool", "platform", "使用", "驾驶", "通勤")
                || token.matches("(?:writes?(?:_code)?|codes?|programs?|develops?|builds?|creates?)_(?:with|using)(?:_.*)?") ? "uses"
            : containsAny(token, "build", "create", "develop", "implement", "author", "构建", "创建", "制作", "开发") ? "builds"
            : containsAny(token, "work", "maintain", "contribute", "responsible", "工作", "维护", "负责") ? "works_on"
            : containsAny(token, "know", "friend", "colleague", "family", "mentor", "认识", "朋友", "同事", "家人") ? "knows"
            : containsAny(token, "visit", "到访") ? "experienced"
            : containsAny(token, "member", "belong", "employ", "part_of", "affiliat", "成员", "隶属", "任职") ? "belongs_to"
            : containsAny(token, "lives_in", "resides_in", "located_in", "居住", "位于") ? "related_to"
            : "";
        if (projected.isBlank()) {
            projected = NORMALIZED_PREDICATES.contains(requested) ? requested : "related_to";
        }
        return new Projection(semantic, projected, !projected.equals(requested));
    }

    private static String normalizeSemantic(String value) {
        if (value == null) return "";
        String result = value.trim().replaceAll("([a-z])([A-Z])", "$1_$2")
            .replaceAll("[^\\p{L}\\p{N}]+", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_|_$", "")
            .toUpperCase(Locale.ROOT);
        return result.length() <= 96 ? result : result.substring(0, 96);
    }

    private static String normalizePredicate(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String value, String... fragments) {
        for (String fragment : fragments) if (value.contains(fragment)) return true;
        return false;
    }
}
