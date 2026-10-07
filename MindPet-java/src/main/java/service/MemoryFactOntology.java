package service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Canonical fact vocabulary shared by the curator prompt, validation, and profile projection. */
public final class MemoryFactOntology {

    private static final Map<String, Set<String>> SCOPES = new LinkedHashMap<>();
    private static final Set<String> ASSERTIONS = Set.of(
        "observed", "confirmed", "reported", "planned", "possible", "uncertain", "negated");
    private static final Set<String> AFFIRMATIVE_ASSERTIONS = Set.of("observed", "confirmed");
    private static final Set<String> CURRENT_PROFILE_SLOTS = Set.of(
        "current_location", "home_location", "occupation_current",
        "relationship_status_current", "current_project");

    static {
        SCOPES.put("current_location", Set.of("current", "planned", "historical", "episodic"));
        SCOPES.put("home_location", Set.of("stable", "historical"));
        SCOPES.put("occupation_current", Set.of("current", "historical"));
        SCOPES.put("relationship_status_current", Set.of("current", "historical"));
        SCOPES.put("current_project", Set.of("current", "planned", "historical"));
        SCOPES.put("preference", Set.of("stable", "episodic", "historical"));
        SCOPES.put("identity", Set.of("stable", "historical"));
        SCOPES.put("experience", Set.of("stable", "episodic", "historical"));
        SCOPES.put("plan", Set.of("planned", "episodic", "historical"));
        SCOPES.put("event", Set.of("planned", "episodic", "historical"));
    }

    private MemoryFactOntology() {}

    public static Set<String> predicates() {
        return SCOPES.keySet();
    }

    public static Set<String> assertions() {
        return ASSERTIONS;
    }

    /** The curator validates first-person evidence before treating a report as an observation. */
    public static String canonicalAssertion(String assertion, String evidence) {
        String value = assertion == null ? "" : assertion.trim().toLowerCase(java.util.Locale.ROOT);
        return "reported".equals(value) && MemoryCuratorFactSupport.isDirectUserStatement(evidence)
            ? "observed" : value;
    }

    public static boolean supportsPredicate(String predicate) {
        return predicate != null && SCOPES.containsKey(predicate.trim());
    }

    public static boolean supports(String predicate, String scope, String assertion) {
        if (predicate == null || scope == null || assertion == null) return false;
        String canonicalPredicate = predicate.trim();
        String canonicalScope = scope.trim().toLowerCase(java.util.Locale.ROOT);
        String canonicalAssertion = assertion.trim().toLowerCase(java.util.Locale.ROOT);
        if (!SCOPES.getOrDefault(canonicalPredicate, Set.of()).contains(canonicalScope)
                || !ASSERTIONS.contains(canonicalAssertion)) return false;
        if ("planned".equals(canonicalScope)) {
            return Set.of("planned", "possible", "uncertain", "negated").contains(canonicalAssertion);
        }
        if ("planned".equals(canonicalAssertion)) return false;
        return true;
    }

    public static boolean isProfileSlot(String predicate) {
        return predicate != null && CURRENT_PROFILE_SLOTS.contains(predicate.trim());
    }

    public static boolean isProjectable(String predicate, String scope, String assertion) {
        if (!isProfileSlot(predicate) || !AFFIRMATIVE_ASSERTIONS.contains(assertion)) return false;
        return "home_location".equals(predicate)
            ? "stable".equals(scope)
            : "current".equals(scope);
    }

    /** Text embedded in the LLM system prompt so generation and validation use one contract. */
    public static String promptContract() {
        StringBuilder out = new StringBuilder("事实本体（只允许使用下列 canonical predicate 与 scope 组合）：\n");
        SCOPES.forEach((predicate, scopes) -> out.append("- ").append(predicate)
            .append(": ").append(String.join(", ", scopes.stream().sorted().toList())).append("\n"));
        out.append("assertion 只能是：").append(String.join(", ", ASSERTIONS.stream().sorted().toList())).append("。\n")
            .append("planned scope 只能搭配 planned、possible、uncertain 或 negated assertion；")
            .append("planned assertion 只能用于 planned scope。\n")
            .append("若用户明确表达计划、打算或已安排，使用 planned assertion；若只是可能、尚未决定或备选去向，使用 possible assertion。\n")
            .append("用户直接陈述自己的确定事实用 observed；明确再次确认同一事实用 confirmed。reported 仅用于转述，不能将第三方经历当作用户事实。\n")
            .append("event/experience 的 value 优先使用用户消息中的连续原文，保留年份、地点、人数与完成状态；不要把我在改写成用户于。\n")
            .append("画像仅允许 home_location + stable，以及 current_location、occupation_current、")
            .append("relationship_status_current、current_project + current；计划和历史事实不得覆盖当前画像。\n")
            .append("城市必须按语义选择：长期的家或老家用 home_location + stable；当前居住地、搬迁前住址和未来拟搬城市都用 current_location，分别搭配 current、historical、planned scope。\n")
            .append("不要把临时居住城市记成 home_location；不要把搬家目的地写成 plan predicate。current_location 的 value 只写城市名，不要写“可能搬去某地”等整句。\n")
            .append("过去职业用 occupation_current + historical；当前职业用 occupation_current + current，value 只写职位名。\n")
            .append("不要发明 predicate。若语义不能准确映射到本体，返回空 facts。\n");
        return out.toString();
    }
}
