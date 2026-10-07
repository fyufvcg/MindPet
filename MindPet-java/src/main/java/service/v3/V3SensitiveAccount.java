package service.v3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Sentence-local account semantics; identifier shape alone never implies sensitivity. */
public final class V3SensitiveAccount {
    public static final String SENSITIVE_TYPE = "ACCOUNT_IDENTIFIER";
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final String LABEL = "(?:(?:登录|测试|合成|用户|系统|内部|临时|私人|私有)*(?:账号|账户)(?:\\s*(?:标识|编号|ID))?"
        + "|登录|测试登录|登录标识|用户标识|账户标识"
        + "|\\b(?:account|login|user[ _-]*(?:id|identifier))(?:[ _-]*(?:id|identifier))?\\b)";
    private static final Pattern CUE = Pattern.compile(LABEL, FLAGS);
    private static final Pattern PRIVATE = Pattern.compile(
        "我的|本人的|我用的|给我的|给我|分配|登录|测试|合成|内部|临时|私人|私有|个人账户|系统给"
        + "|\\b(?:my|our|private|login|test|demo|internal|system|temporary|synthetic|assigned)\\b", FLAGS);
    private static final Pattern PUBLIC = Pattern.compile("公开|公共展示|官方|客服|\\bpublic\\b|GitHub|项目.{0,12}owner|昵称", FLAGS);
    private static final Pattern SENTENCE = Pattern.compile("[^。；;！!？?\\r\\n]+", FLAGS);
    private static final Pattern TOKEN = Pattern.compile("(?<![A-Za-z0-9_@.\\-])[A-Za-z0-9][A-Za-z0-9_@.\\-]*", FLAGS);
    private static final Pattern ASSIGNMENT = Pattern.compile(LABEL
        + "\\s*(?:(?<!不)是|为|叫做|叫|名为|设成(?:了)?|设置(?:为|成)|分配为|[:：=]|(?:is|equals|named|called)\\b|(?<=(?:ID|标识|编号))\\s)"
        + "\\s*[\\\"'“‘「『\\[(（]?\\s*(?<value>[\\p{L}\\p{N}][\\p{L}\\p{N}_@.\\-]*?)"
        + "(?=[\\s，,。；;！!？?\\\"'”’」』\\])）]|$)", FLAGS);
    private static final Set<String> WORDS = Set.of("my","our","is","it","this","that","the","a","an","id","identifier",
        "account","login","user","test","demo","internal","private","public","synthetic","system","temporary",
        "named","called","equals","please","save","remember","as","use","for","to","of","in","not","do","i","and","or");

    private V3SensitiveAccount() {}
    public record SensitiveSpan(int start, int end, String value, String sensitiveType) {}

    public static List<SensitiveSpan> identifiers(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<SensitiveSpan> spans = new ArrayList<>();
        Matcher sentence = SENTENCE.matcher(text);
        while (sentence.find()) {
            String window = sentence.group();
            // Do not let the identifier's own prefix supply its semantic/ownership evidence.
            String semanticWindow = TOKEN.matcher(window).replaceAll(match ->
                CUE.matcher(match.group()).matches() || !match.group().matches(".*[_@.\\-0-9].*")
                    ? Matcher.quoteReplacement(match.group()) : " ");
            if (!CUE.matcher(semanticWindow).find()) continue;
            String asserted = window.replaceAll("(?:不是|并非|而非|不属于|\\bnot\\b)[^，,。；;]{0,80}", "");
            if (PUBLIC.matcher(window).find() && !PRIVATE.matcher(asserted.replaceAll("我的|本人的|\\bmy\\b", "")).find()) continue;
            Matcher assigned = ASSIGNMENT.matcher(window);
            while (assigned.find()) {
                String value = assigned.group("value");
                if (valid(value)) add(spans, sentence.start()+assigned.start("value"), value);
            }
            if (!PRIVATE.matcher(semanticWindow).find()) continue;
            Matcher tokens = TOKEN.matcher(window);
            while (tokens.find()) {
                String value = tokens.group().replaceAll("[.\\-]+$", "");
                if (!valid(value)) continue;
                int start = tokens.start(), end = start + value.length();
                String local = window.substring(Math.max(0,start-160), Math.min(window.length(),end+160));
                // A+B+C, independent of order; commas/pronouns/modifiers do not break the window.
                if (CUE.matcher(local).find() && PRIVATE.matcher(local).find()) add(spans,sentence.start()+start,value);
            }
        }
        spans.sort(Comparator.comparingInt(SensitiveSpan::start));
        return List.copyOf(spans);
    }

    private static boolean valid(String value) {
        return !value.isBlank() && !WORDS.contains(value.toLowerCase(Locale.ROOT))
            && !CUE.matcher(value).matches() && !value.matches("(?:是|为|我的|账户|账号|设置|权限|管理|安全|标识|编号)+");
    }
    private static void add(List<SensitiveSpan> spans,int start,String value) {
        if (spans.stream().noneMatch(s -> s.start()==start && s.value().equals(value)))
            spans.add(new SensitiveSpan(start,start+value.length(),value,SENSITIVE_TYPE));
    }
    public static boolean containsIdentifier(String text) { return !identifiers(text).isEmpty(); }
}
