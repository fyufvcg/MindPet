package service;

import java.util.Set;
import java.util.regex.Pattern;

/** Curator-only evidence interpretation; it does not rank, forget, or write raw memories. */
public final class MemoryCuratorFactSupport {
    private MemoryCuratorFactSupport() {}

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
        return surface.isBlank() ? "" : MemoryEvidenceCoverage.supportingSentence(evidence, surface);
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
        return MemoryEvidenceCoverage.supports(content, coverageEvidence(fact));
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
            if ("possible".equals(fact.assertion()) && !target.matches("(?s).*(?:可能|备选|考虑|尚未决定).*")) return false;
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
