package service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** Coverage for retirement, intentionally separate from fact support and scoring. */
public final class MemorySourceCoverage {
    private MemorySourceCoverage() {}
    private static final Pattern FRAME = Pattern.compile(
        "(?:我的沟通偏好没有变|答复方式还是|回复形式我偏好|我比较习惯|我希望回复|之后回答请|请继续采用|再说一次|的答复顺序|这样的回答方式|我仍偏好|我的沟通偏好是|我的偏好是|我偏好|我希望|补充确认|再次确认|再核对一次|最后核对|更正近况|现在的职位是|我的当前工作岗位是|我目前的职位是|我现在的职业是|我现在担任|我目前担任|我现在负责|我目前负责|我正在负责|我现在参与|我目前参与|我曾负责|以前负责|我做过|那时从事|我从事|现阶段转任|我目前工作是|我的现职是|现在的岗位是|长期住址是|我的老家在|我的家乡是|我长期的家在|我现在住在|我目前住在|目前我一直住在|我目前一直住在|我的现居城市是|目前生活在|现在住在|之前住在|以前住在|曾经住在|搬家前我住在|我以前住在|我已经搬到|我已经搬回|我现居|我来自|现居地为|当前项目的名称是|当前项目是|长期项目记录仍是|项目状态更新|仍由我负责|我继续负责|工作内容是|现在主要做|岗位上继续负责|我可能参加|我计划参加|我已安排报名|我已经参加|我已经完成|我参加过|我完成了|我曾经|我已经取消|我可能搬去|我以后可能搬去|未来搬去|以后可能搬去|仍只是备选计划|仍是可能的未来安排|尚未执行|没有实际发生|目前只是考虑|还没有搬|它不是现居地|只是考虑过的去向|我只是可能去|关于搬家的事|我以前做|过去做过|已经是过去的工作|只属于历史|目前仍住在|我仍住在|我仍从事|我目前从事|我此前担任|我现在重新住在|我目前重新担任)");

    public static boolean fullyCovered(String content, List<MemoryEvidenceCoverage.Evidence> facts) {
        if (content == null || content.isBlank() || facts == null || facts.isEmpty()) return false;
        // Replace complete value spans before splitting, so punctuation inside a value is retained.
        List<MemoryEvidenceCoverage.Evidence> supported = facts.stream()
            .filter(f -> MemoryEvidenceCoverage.supports(f.text(), f))
            .sorted(Comparator.comparingInt((MemoryEvidenceCoverage.Evidence f) -> f.value().length()).reversed())
            .toList();
        List<String> marks = new ArrayList<>();
        List<int[]> spans = new ArrayList<>();
        for (MemoryEvidenceCoverage.Evidence f : supported) {
            String surface = f.surfaceValue();
            if (surface == null || surface.isBlank()) surface = MemoryValueNormalizer.findSurface(f.predicate(), f.value(), f.text());
            if (surface.isBlank()) continue;
            // Authorize only the cited occurrence; another occurrence may concern another person or state.
            int quoteAt = content.indexOf(f.text());
            int valueAt = f.text().indexOf(surface);
            if (quoteAt < 0 || valueAt < 0 || content.indexOf(f.text(), quoteAt + 1) >= 0) continue;
            int start = quoteAt + valueAt;
            int spanEnd=start+surface.length();
            if (spans.stream().anyMatch(s -> start < s[1] && spanEnd > s[0])) continue;
            spans.add(new int[]{start,start+surface.length()});
        }
        if (spans.isEmpty()) return false;
        String residual = content;
        spans.sort((a,b)->Integer.compare(b[0],a[0]));
        for(int[] span:spans) {
            String marker="zzcovered"+marks.size()+"zz";marks.add(marker);
            residual=residual.substring(0,span[0])+marker+residual.substring(span[1]);
        }
        for (String part : MemoryEvidenceCoverage.normalize(residual).split("[，,；;。！？!?\\n]+")) {
            if (part.isBlank()) continue;
            String withoutValues = part;
            for (String marker : marks) withoutValues = withoutValues.replace(marker, "");
            String remainder = FRAME.matcher(withoutValues).replaceAll("")
                .replaceAll("[：:、（）()‘’“”'\\\"\\-]", "");
            if (!remainder.isBlank()) return false;
        }
        // Every source value must be supported locally; unknown names, numbers or extra details remain.
        return true;
    }

    /** Remove only complete independent sentences; a partially understood sentence stays verbatim. */
    public static String residual(String content,List<MemoryEvidenceCoverage.Evidence> facts) {
        StringBuilder kept=new StringBuilder();boolean removed=false;
        for(String sentence:content.split("(?<=[。！？!?；;])|\\n")) {
            if(sentence.isBlank())continue;
            if(fullyCovered(sentence,facts))removed=true;else kept.append(sentence);
        }
        String result=kept.toString().trim();
        return removed&&!result.isBlank()?result:"";
    }
}
