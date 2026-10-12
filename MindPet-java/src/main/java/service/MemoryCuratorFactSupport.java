package service;

import java.util.Set;
import java.util.regex.Pattern;

/** Curator-only evidence interpretation; it does not rank, forget, or write raw memories. */
public final class MemoryCuratorFactSupport {
    private MemoryCuratorFactSupport() {}

    public static final String RULE_VERSION = "curator-support-20261009-v5";

    public static boolean isDirectUserStatement(String evidence) {
        String text = MemoryContentSafety.normalizeEvidence(evidence);
        return text.matches("(?s)^(?:我(?!们)|目前我|现在我|之前我|以前我|说起老家[，,]?我|回复形式我|之后回答请|回答请|回复请).*")
            && !text.matches("(?s).*(?:听说|据说|转述|别人|朋友|同事|妈妈|爸爸|父母|妻子|丈夫|他说|她说).*");
    }

    public static String canonicalValue(String predicate, String value) {
        predicate = predicate == null ? "" : predicate.trim();
        String canonical = MemoryValueNormalizer.canonical(predicate, value);
        // Only change grammatical subject/preposition. All entities, dates and numbers stay exact.
        if (Set.of("event", "experience").contains(predicate)) {
            canonical = canonical.replaceFirst("^用户(?:于|在)", "我在").replaceFirst("^用户", "我");
        }
        return canonical;
    }

    public static boolean equivalentSurface(String predicate, String value, String surface) {
        return MemoryValueNormalizer.equivalent(predicate, canonicalValue(predicate, value), canonicalValue(predicate, surface));
    }

    private static String localEvidence(String predicate, String value, String evidence) {
        String surface = MemoryValueNormalizer.findSurface(predicate, value, evidence);
        return supportingStatement(evidence, surface);
    }

    /** Preserve comma-linked qualifiers, but never cross a sentence or speaker boundary. */
    public static String supportingStatement(String content, String surface) {
        if (content == null || surface == null || surface.isBlank()) return "";
        int at = content.indexOf(surface);
        if (at < 0) return "";
        int start = at, end = at + surface.length();
        while (start > 0 && "。！？!?；;\n".indexOf(content.charAt(start - 1)) < 0) start--;
        while (end < content.length() && "。！？!?；;\n".indexOf(content.charAt(end)) < 0) end++;
        // Repeated values can describe one unchanged state. Crossing a sentence remains ambiguous.
        return content.lastIndexOf(surface) + surface.length() <= end ? content.substring(start, end) : "";
    }

    /** A malformed optional surface hint never substitutes for the exact canonical value. */
    public static String sourceSurface(String predicate, String value, String evidence, String hint) {
        String exact = MemoryValueNormalizer.findSurface(predicate, value, evidence);
        if (hint != null && !hint.isBlank() && evidence.contains(hint) && equivalentSurface(predicate, value, hint))
            return hint;
        return exact;
    }

    /** Repetition is safe only within the same directly affirmed current-location statement.
     * Historical, future, third-party and contrast clauses still require a narrower repaired quote. */
    public static boolean repeatedSurfaceSupported(String predicate, String evidence, String surface,
                                                    String assertion, String scope) {
        if (evidence.indexOf(surface) == evidence.lastIndexOf(surface)) return true;
        String local = supportingStatement(evidence, surface);
        if (!"current_location".equals(predicate) || !"current".equals(scope)
                || !Set.of("observed", "confirmed", "reported").contains(assertion) || local.isBlank()
                || local.matches("(?s).*(?:朋友|同事|父母|妈妈|爸爸|听说|据说|以前|过去|之前|原来|曾经|老家|家乡|旧址|搬家前|搬迁前|当时|那时|明天|以后|将来|计划|可能|也许|候选|备选|但|不过|而是|却|不是|不再).*" )
                || !MemoryContentSafety.polarityConsistent(local, surface, assertion, scope)) return false;
        for (int at = local.indexOf(surface); at >= 0; at = local.indexOf(surface, at + surface.length())) {
            int start = at, end = at + surface.length();
            while (start > 0 && "，,。！？!?；;".indexOf(local.charAt(start-1)) < 0) start--;
            while (end < local.length() && "，,。！？!?；;".indexOf(local.charAt(end)) < 0) end++;
            String clause = local.substring(start,end);
            if (!clause.matches("(?s).*(?:我|目前|现在|当前|现居).*(?:住|居|搬回|搬到).*")) return false;
        }
        return true;
    }

    /** A decision is still open when enrolment is only temporarily withheld.
     * Explicit cancellation or denial of an accomplished activity remains a separate state. */
    public static boolean hasOpenDecision(String text) {
        return text.matches("(?s).*(?:还|尚|仍)?(?:没有|没|未)(?:最终)?(?:决定|确定|选定|定下).*" );
    }

    public static String transitionSlot(String predicate, String scope, String value) {
        return MemoryFactOntology.isProfileSlot(predicate) ? predicate + "|" + scope
            : Set.of("plan", "event").contains(predicate) ? predicate + "|" + canonicalValue(predicate, value) : "";
    }

    public static boolean hasPredicateCue(String content, String predicate) {
        if (MemoryEvidenceCoverage.hasPredicateCue(content, predicate)) return true;
        String text = MemoryEvidenceCoverage.normalize(content);
        return switch (predicate) {
            case "occupation_current" -> text.matches("(?s).*(?:我是|我叫[^，,。]+[，,]是|我现在是|我目前是)(?:一名|一个|位)?[^，,。；;]{1,30}(?:师|员|学徒|志愿者|助教|学生|研究生|店主|工匠).*" );
            case "current_project" -> text.contains("课题");
            case "plan" -> text.matches("(?s).*(?:计划|打算|准备|安排|我会|候选|备选|未决定|没决定|取消|改期|延期|有效期|有效|许可).*" );
            case "event" -> text.matches("(?s).*(?:计划|打算|准备|安排|我会|候选|备选|未决定|没决定|取消|改期|延期|有效期|有效|许可|续期).*" )
                || isRoutine(text) || isStateChange(text);
            case "experience" -> text.matches("(?s).*(?:我发现|我的经验|我体会|我意识到|我总结).*" ) || isRoutine(text);
            case "preference" -> isPreferenceStatement(text);
            default -> false;
        };
    }

    /** Recognize sentence structure and ownership, without enumerating individual occupations/tasks. */
    static boolean isPreferenceStatement(String text) {
        return text.matches("(?s).*(?:我.*(?:习惯|顺序没变|还是先|长期|偏好)|(?:遇到|没有聊过|回答|回复).*(?:你先|请|告诉我)).*")
            || text.matches("(?s).*(?:我|我的).*(?:一直|长期|总是|每次|顺序).*(?:先|优先).*(?:再|然后|后).*")
            || text.matches("(?s).*(?:暂时)?(?:别|不要)(?:替我|帮我|为我)[^。！？]{2,}.*");
    }

    static boolean isRoutine(String text) {
        return text.matches("(?s).*我[^。！？;；]*(?:每天|每日|每周|每月|每次|每晚)[^。！？;；]*(?:练习|观测|试制|制作|做|检查|整理|学习|跑步|工作)[^。！？;；]*.*");
    }

    static boolean isStateChange(String text) {
        return text.matches("(?s).*(?:我|我的)[^。！？;；]*(?:已经|已|现在|当前|目前)?(?:转到|换到|改为|改成|改做|恢复)[^。！？;；]+.*");
    }

    /** A current routine is an observation, never a future intention merely because it repeats. */
    public static boolean routineMisclassifiedAsPlan(String evidence, String predicate, String assertion) {
        String text = MemoryEvidenceCoverage.normalize(evidence);
        return ("plan".equals(predicate) || "planned".equals(assertion)) && isRoutine(text)
            && !text.matches("(?s).*(?:打算|计划|准备|我会|将会|以后|下周|下个月|明天|后天|可能|也许|候选|备选).*");
    }

    /** The generic guard inspects around the value. Full activity quotes also contain
     * their state inside the value; handle those quotes here without relaxing core guards. */
    public static boolean polarityConsistent(String evidence, String surface, String assertion, String scope, String predicate) {
        String local = supportingStatement(evidence, surface);
        if (local.isBlank()) return false;
        if (Set.of("event", "plan").contains(predicate)) {
            String normalizedLocal = MemoryContentSafety.normalizeEvidence(local);
            if (hasOpenDecision(normalizedLocal)) {
                boolean cancelled = normalizedLocal.matches("(?s).*(?<!没有)(?<!没)(?<!未)(?<!不)(?:取消|放弃|撤回|终止).*" );
                boolean deniedCompletion = normalizedLocal.matches("(?s).*(?:没有|没|未|从未|尚未)(?:参加过|完成|提交).*" );
                boolean finalRefusal = normalizedLocal.matches("(?s).*(?:决定不|不再)(?:参加|报名).*" )
                    || normalizedLocal.replaceAll("暂时(?:不|没有|没)(?:报名|参加)", "")
                        .matches("(?s).*(?:不|没有|没)(?:报名|参加).*" );
                return !cancelled && !deniedCompletion && !finalRefusal
                    && Set.of("possible", "uncertain").contains(assertion);
            }
            String text = MemoryContentSafety.normalizeEvidence(surface);
            // A state inside the literal value belongs to this fact, not a neighbouring clause.
            boolean cancelled = text.matches("(?s).*(?<!没有)(?<!没)(?<!未)(?<!不)(?:取消|放弃|撤回|终止).*" )
                || text.matches("(?s).*(?:不再参加|决定不参加|不再计划).*" );
            boolean possible = text.matches("(?s).*(?:可能|也许|或许|候选|备选|不确定|没决定|未决定|未选定|没选定).*" );
            boolean deniedActivity = text.matches("(?s).*(?:没有|尚未|从未|不再|未|没|不)(?:参加|报名|完成|提交|安排|计划).*" );
            if (deniedActivity && !possible) cancelled = true;
            if (cancelled || possible) {
                int at = local.indexOf(surface);
                String outside = local.substring(0, at) + local.substring(at + surface.length());
                if (outside.matches("(?s).*(?:可能|也许|或许|没有|尚未|从未|不确定|没决定|未决定).*")) return false;
                if (cancelled && possible) return false; // Mixed scope needs a narrower repaired quote.
                if (cancelled) return "negated".equals(assertion);
                return Set.of("possible", "uncertain").contains(assertion);
            }
            if (local.matches("(?s).*(?:我会|打算|计划|准备).*(?:参加|报名|提交|采样|去|做).*" )
                    && !Set.of("planned", "possible", "uncertain").contains(assertion)) return false;
        }
        return MemoryContentSafety.polarityConsistent(local, surface, assertion, scope);
    }

    public static MemoryEvidenceCoverage.Evidence coverageEvidence(MemoryEvidenceCoverage.Evidence fact) {
        String local = localEvidence(fact.predicate(), fact.value(), fact.text());
        if ("event".equals(fact.predicate()) && !local.isBlank()
                && !MemoryEvidenceCoverage.supports(fact.text(), fact)
                && Set.of("observed", "confirmed", "reported").contains(fact.assertion())
                && Set.of("episodic", "historical").contains(fact.scope())
                && (local.contains("完成") || local.contains("参加过") || local.contains("已经参加"))
                && !local.matches("(?s).*(?:没|未|不|尚未|可能|也许|打算|计划).*(?:完成|参加).*") ) {
            // A directly described completed activity is also valid experience evidence.
            MemoryEvidenceCoverage.Evidence experience = new MemoryEvidenceCoverage.Evidence(
                "experience", fact.value(), fact.scope(), fact.assertion(), fact.text(), fact.surfaceValue());
            if (MemoryEvidenceCoverage.supports(fact.text(), experience)) return experience;
        }
        return fact;
    }

    public static boolean supports(String content, MemoryEvidenceCoverage.Evidence fact) {
        return rejectionReason(content, fact).isBlank();
    }

    /** Stable subreason is persisted and sent to directed repair; an empty reason means supported. */
    public static String rejectionReason(String content, MemoryEvidenceCoverage.Evidence fact) {
        String surface = MemoryValueNormalizer.findSurface(fact.predicate(), fact.value(), content);
        String local = supportingStatement(content, surface);
        if (surface.isBlank()) return "value_surface_missing";
        if (local.isBlank() || !repeatedSurfaceSupported(fact.predicate(), content, surface, fact.assertion(), fact.scope()))
            return "local_statement_ambiguous";
        if (MemoryContentSafety.looksSensitive(local)) return "sensitive_statement";
        if ("current".equals(fact.scope()) && local.matches("(?s).*(?:曾任|当时|那时|以前|过去).*" )
                && !local.matches("(?s).*(?:现在|目前|当前|现任).*")) return "historical_statement_in_current_slot";
        if ("experience".equals(fact.predicate()) && isPreferenceStatement(MemoryEvidenceCoverage.normalize(local)))
            return "workflow_order_is_preference";
        if ("experience".equals(fact.predicate()) && MemoryEvidenceCoverage.normalize(surface).equals(MemoryEvidenceCoverage.normalize(local))) {
            boolean uncertain=local.matches("(?s).*(?:可能|也许|或许|不确定|未决定|没决定|候选|备选).*" );
            boolean denied=local.matches("(?s).*(?:没有|从未|尚未|不再|没|未)(?:做|参加|完成|练习|观测|试制|学习).*" );
            if (uncertain && !Set.of("possible","uncertain").contains(fact.assertion())) return "local_polarity_mismatch";
            if (denied && !uncertain && !"negated".equals(fact.assertion())) return "local_polarity_mismatch";
        }
        if (!polarityConsistent(local, surface, fact.assertion(), fact.scope(), fact.predicate())) return "local_polarity_mismatch";
        if (routineMisclassifiedAsPlan(local, fact.predicate(), fact.assertion())) return "routine_is_not_future_plan";
        if (MemoryEvidenceCoverage.supports(content, coverageEvidence(fact))) return "";
        // Supplement only documented Curator interpretations. Location/core ontology guards stay unchanged.
        if (!Set.of("occupation_current", "current_project", "preference", "plan", "event", "experience").contains(fact.predicate())) return "core_evidence_guard";
        if (local.matches("(?s).*(?:听说|据说|转述|他说|她说|朋友|同事|妈妈|爸爸|妻子|丈夫).*")) return "third_party_statement";
        if ("current".equals(fact.scope()) && local.matches("(?s).*(?:曾任|当时|那时|以前|过去).*" )
                && !local.matches("(?s).*(?:现在|目前|当前|现任).*")) return "historical_statement_in_current_slot";
        return hasPredicateCue(local, fact.predicate()) ? "" : "predicate_cue_missing";
    }

    /** A fact may replace its source only when its searchable text retains critical details. */
    public static boolean preservesCriticalDetails(MemoryEvidenceCoverage.Evidence fact, String projection) {
        if (projection == null || projection.isBlank()) return false;
        String source = MemoryEvidenceCoverage.normalize(fact.text());
        String target = MemoryEvidenceCoverage.normalize(projection);
        if (MemoryValueNormalizer.findSurface(fact.predicate(), fact.value(), projection).isBlank()
                && !MemoryEvidenceCoverage.normalize(canonicalValue(fact.predicate(), projection))
                    .contains(MemoryEvidenceCoverage.normalize(canonicalValue(fact.predicate(), fact.value())))) return false;
        // Exact number tokens prevent 27 people from being mistaken for 127, or period 19 for 190.
        var numbers = Pattern.compile("[0-9]+(?:[.,][0-9]+)?").matcher(source);
        while (numbers.find()) {
            if (!Pattern.compile("(?<![0-9])" + Pattern.quote(numbers.group()) + "(?![0-9])")
                    .matcher(target).find()) return false;
        }
        if (source.contains("搬家前") && !target.contains("搬家前")) return false;
        if (source.contains("搬迁前") && !(target.contains("搬迁前") || target.contains("搬家前"))) return false;
        if (Set.of("plan", "event", "experience").contains(fact.predicate())) {
            boolean cancelled = source.matches("(?s).*(?:取消|放弃|不再计划|不再参加|决定不参加).*" );
            boolean completed = source.contains("完成")
                && !source.matches("(?s).*(?:没|未|不|尚未|可能|也许|打算|计划).*完成.*");
            if (cancelled && !target.matches("(?s).*(?:取消|放弃|不再计划|不再参加|决定不参加).*")) return false;
            if (completed && (!target.contains("完成")
                    || target.matches("(?s).*(?:没|未|不|尚未|可能|也许|打算|计划).*完成.*"))) return false;
            if ("possible".equals(fact.assertion()) && !target.matches("(?s).*(?:可能|候选|备选|考虑|没决定|未决定|未选定).*")) return false;
        }
        return true;
    }

    /** Protect personal activity changes without retaining ordinary completed tool tasks. */
    public static boolean hasActivityStatusToPreserve(String content) {
        String text = MemoryEvidenceCoverage.normalize(content);
        boolean status = text.matches("(?s).*(?:取消|放弃|完成|参加过|已经参加|已参加|延期|推迟|改期|终止|撤回|不再参加|决定不参加).*" );
        if (!status) return false;
        boolean durableObject = text.matches("(?s).*(?:课程|培训|考试|报名|会议|评审|项目|合同|预约|行程|旅行|手术|治疗|比赛|工作|入职|离职|毕业|学位|证书|资格).*" );
        boolean personalChange = text.matches("(?s).*(?:我(?:已经|已|决定|后来|现在|目前)?(?:取消|放弃|完成|参加过|已经参加|已参加|延期|推迟|终止|撤回|不再参加)|我的.*(?:取消|完成|延期|推迟)).*" );
        boolean ephemeral = text.matches("(?s).*(?:临时|一次性|这次计算|临时计算|页码|上一条提醒|这条提醒|工具结果|文件排序).*" );
        return durableObject || (personalChange && !ephemeral);
    }

    /** A deterministic short expression based on validated evidence, never unverified LLM prose. */
    public static String retrievalText(String predicate, String value, String scope, String assertion, String evidence) {
        String raw = localEvidence(predicate, value, evidence == null ? "" : evidence);
        if (Set.of("plan", "event", "experience").contains(predicate) && !raw.isBlank()
                && raw.matches("(?s).*(?:取消|延期|改期|未改期|未决定|有效期|失效|截止|尚未完成).*")) {
            // Use the validated local source to retain cancellation plus rescheduling/validity qualifiers.
            // Critical-detail validation still decides whether it can replace any original source.
            return "事项原文：" + raw;
        }
        if ("negated".equals(assertion) && Set.of("plan", "event").contains(predicate)) {
            String prefix = "historical".equals(scope) ? "用户曾" : "用户已";
            if (raw.contains("取消")) return prefix + "取消原计划：" + value;
            if (raw.contains("放弃")) return prefix + "放弃原计划：" + value;
        }
        if (!"negated".equals(assertion)) {
            if ("home_location".equals(predicate)) {
                String historical = "historical".equals(scope) ? "过去的" : "";
                if (raw.contains("老家")) return "用户" + historical + "老家在：" + value;
                if (raw.contains("家乡")) return "用户" + historical + "家乡是：" + value;
            }
            if ("current_location".equals(predicate) && "historical".equals(scope)) {
                if (raw.contains("搬家前") || raw.contains("搬迁前")) return "用户搬家前住在：" + value;
            }
            if ("preference".equals(predicate) && "stable".equals(scope)
                    && Set.of("observed", "confirmed", "reported").contains(assertion)) {
                return "用户偏好：" + value;
            }
            if (Set.of("plan", "event", "experience").contains(predicate)
                    && Set.of("episodic", "historical").contains(scope)
                    && Set.of("observed", "confirmed", "reported").contains(assertion)) {
                if (raw.contains("完成") && !raw.matches("(?s).*(?:没|未|不|尚未|可能|也许|打算|计划).*完成.*")) {
                    if (value.startsWith("我") && value.contains("完成")) return "用户" + value.substring(1);
                    return "用户已完成经历：" + value;
                }
                if ((raw.contains("参加过") || raw.contains("已经参加"))
                        && !raw.matches("(?s).*(?:没|未|不|尚未|可能|也许|打算|计划).*参加.*")) {
                    if (value.startsWith("我") && value.contains("参加")) return "用户" + value.substring(1);
                    return "用户已参加经历：" + value;
                }
            }
        }
        return MemoryCorpusCompactionService.factContent(predicate, value, "", scope, assertion);
    }
}
