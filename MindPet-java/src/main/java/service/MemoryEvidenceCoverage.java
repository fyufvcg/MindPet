package service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Production fact support. Offline evaluation must use its own frozen rubric. */
public final class MemoryEvidenceCoverage {
    private MemoryEvidenceCoverage() {}

    public record Evidence(String predicate, String value, String scope, String assertion, String text, String surfaceValue) {
        public Evidence(String predicate, String value, String scope, String assertion, String text) {
            this(predicate, value, scope, assertion, text, MemoryValueNormalizer.findSurface(predicate, value, text));
        }
    }

    public static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    public static boolean supports(String content, Evidence fact) {
        String surface = MemoryValueNormalizer.findSurface(fact.predicate(), fact.value(), content);
        if (surface.isBlank()) return false;
        String local = supportingSentence(content, surface);
        String clause = normalize(local);
        String value = normalize(surface);
        if (contains(clause, "妈妈", "爸爸", "父母", "朋友", "同事", "妻子", "丈夫")) return false;
        if ("current_location".equals(fact.predicate()) && contains(clause, "老家", "家乡", "长期的家")
                && !contains(clause, "现在住", "目前住", "现居", "当前住", "搬到")) return false;
        if ("current".equals(fact.scope()) && contains(clause, "搬家前", "搬迁前", "过去的", "曾任", "那时", "当时")
                && !contains(clause, "现在", "目前", "当前", "现居", "现职", "现任")) return false;
        return !value.isBlank() && clause.contains(value)
            && hasPredicateCue(clause, fact.predicate())
            && MemoryContentSafety.polarityConsistent(local, surface, fact.assertion(), fact.scope());
    }

    public static String supportingSentence(String content, String surface) {
        if (content == null || surface == null || surface.isBlank()) return "";
        int at = content.indexOf(surface);
        if (at < 0) return "";
        int start = at, end = at + surface.length();
        while (start > 0 && "，,。！？!?；;\n".indexOf(content.charAt(start - 1)) < 0) start--;
        while (end < content.length() && "，,。！？!?；;\n".indexOf(content.charAt(end)) < 0) end++;
        return content.substring(start, end);
    }

    /** Every clause must express a supported fact; unknown residual information prevents retirement. */
    public static boolean fullyCovered(String content, List<Evidence> facts) {
        return MemorySourceCoverage.fullyCovered(content, facts);
    }

    static boolean legacyClauseCovered(String content, List<Evidence> facts) {
        if (content == null || content.isBlank() || facts.isEmpty()) return false;
        boolean covered = false;
        for (String clause : content.split("[，,；;。！？!?\\n]+")) {
            String normalized = normalize(clause);
            if (normalized.isBlank() || Set.of("补充确认", "再次确认", "更正近况", "再核对一次", "说起老家").contains(normalized)) continue;
            boolean matched = facts.stream().anyMatch(fact ->
                normalize(fact.text()).contains(normalized) && supports(clause, fact)
                    && !hasOtherPredicateCue(clause, fact.predicate()) && hasOnlyFactScaffolding(clause, fact));
            if (!matched) return false;
            covered = true;
        }
        return covered;
    }

    static boolean hasOnlyFactScaffolding(String clause, Evidence fact) {
        String remainder = normalize(clause).replace(normalize(fact.value()), "");
        // Unknown words, numbers, duration, people, or an extra object are residual information.
        // This allowlist is deliberately conservative; embedding similarity never authorizes removal.
        String[] allowed = switch (fact.predicate()) {
            case "current_location" -> new String[]{"我目前一直住在", "我的现居城市是", "是我目前住的城市", "是我目前的住址",
                "我已经搬回", "我已经搬到", "我现在住在", "我目前住在", "我目前在", "我现在在", "现在的住处在", "我现居", "用户现居城市",
                "用户当前居住在", "用户目前居住在", "用户过去居住城市", "之前住在", "以前住在", "曾经住在", "搬家前住在", "居住", "这里", "一直", "的城市"};
            case "home_location" -> new String[]{"我的老家在", "我的家乡是", "是我的家乡", "我长期的家在", "说起老家", "我来自",
                "用户老家在", "用户长期住址", "我的家在"};
            case "occupation_current" -> new String[]{"我目前的职位是", "我的当前工作岗位是", "我现在的职业是", "现在我担任",
                "我现在担任", "我目前担任", "我现在的职位是", "用户当前职位", "用户曾任职位", "我曾担任", "以前我担任"};
            case "preference" -> new String[]{"回复形式我偏好", "我喜欢用", "我习惯先看到", "之后回答请保持", "我希望回复",
                "我偏好", "我喜欢", "用户偏好", "用户原偏好", "回答", "回复", "用"};
            case "plan", "event" -> new String[]{"我已经参加", "我已经完成", "我参加过", "我完成了", "我已经取消", "我已经放弃参加", "我不再计划报名", "我决定不参加了",
                "我可能参加", "只是我考虑的选项", "我还没决定是否报名", "我也许会去学", "我计划参加", "我已安排报名",
                "我已经计划参加", "我决定参加", "接下来的计划是参加", "我计划", "我打算", "我准备", "的计划", "用户已否定或取消原计划",
                "用户曾考虑", "用户曾计划"};
            case "identity" -> new String[]{"我的名字是", "我的名字叫", "我叫", "我是", "身份是"};
            case "experience" -> new String[]{"我曾经", "我曾", "我参加过", "我完成过", "我做过", "我学过"};
            case "relationship_status_current" -> new String[]{"我目前", "我现在", "我的感情状态是", "我的婚姻状态是", "我已经", "我是"};
            case "current_project" -> new String[]{"我目前负责", "我现在负责", "我正在负责", "我目前参与", "我现在参与",
                "用户当前项目", "用户曾负责项目", "我曾负责", "以前负责"};
            default -> new String[0];
        };
        for (String word : allowed) remainder = remainder.replace(word, "");
        return remainder.replaceAll("[：:、（）()\\-]", "").isBlank();
    }

    public static boolean hasPredicateCue(String content, String predicate) {
        String value = normalize(content);
        String[] cues = switch (predicate == null ? "" : predicate) {
            case "current_location" -> new String[]{"住", "居", "搬", "落脚", "在", "livesin", "residesin"};
            case "home_location" -> new String[]{"老家", "家乡", "家在", "长期住", "我来自", "home"};
            case "occupation_current" -> new String[]{"工作", "职位", "岗位", "职业", "任职", "担任", "曾任", "从事", "转任", "做过", "现职", "job", "workas"};
            case "current_project" -> new String[]{"项目", "负责", "参与", "project"};
            case "relationship_status_current" -> new String[]{"单身", "已婚", "结婚", "伴侣", "恋爱", "离婚"};
            case "preference" -> new String[]{"喜欢", "偏好", "习惯", "希望", "回复", "回答", "答复", "沟通", "prefer"};
            case "identity" -> new String[]{"我叫", "名字", "我是", "身份", "name"};
            case "experience" -> new String[]{"经历", "曾", "参加", "完成", "做过", "学过"};
            case "plan" -> new String[]{"计划", "打算", "安排", "准备", "可能", "考虑", "报名", "也许", "取消", "放弃", "决定", "参加", "完成", "plan"};
            case "event" -> new String[]{"会议", "评审", "活动", "发生", "举行", "安排", "参加", "取消", "event"};
            default -> new String[0];
        };
        for (String cue : cues) if (value.contains(cue)) return true;
        return false;
    }

    private static boolean hasOtherPredicateCue(String content, String predicate) {
        String value = normalize(content);
        if (!Set.of("current_location", "home_location").contains(predicate)
                && contains(value, "住在", "现居", "搬到", "老家")) return true;
        if (!"occupation_current".equals(predicate) && contains(value, "职位", "岗位", "职业", "任职")) return true;
        if (!"current_project".equals(predicate) && contains(value, "项目", "负责")) return true;
        if (!"preference".equals(predicate) && contains(value, "偏好", "喜欢", "习惯")) return true;
        // A linked person's details or unparsed conjunction may contain another independent fact.
        return contains(value, "家人", "妈妈", "爸爸", "父母", "朋友", "同事", "另外", "而且", "同时", "并且");
    }

    private static boolean contains(String value, String... cues) {
        for (String cue : cues) if (value.contains(cue)) return true;
        return false;
    }
}
