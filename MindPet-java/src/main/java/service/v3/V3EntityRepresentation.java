package service.v3;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Projects semantic entity categories into the existing MindPet entity representation. No brand registry. */
public final class V3EntityRepresentation {
    private static final Map<String,String> KINDS = Map.ofEntries(
        Map.entry("APPLICATION", "tool"), Map.entry("DEVICE", "tool"), Map.entry("VEHICLE", "tool"),
        Map.entry("PROGRAMMING_LANGUAGE", "technology"), Map.entry("FRAMEWORK", "technology"),
        Map.entry("DATABASE", "technology"), Map.entry("RUNTIME", "technology"),
        Map.entry("NATURAL_LANGUAGE", "topic"));
    private static final Pattern TECHNOLOGY_CATEGORY = Pattern.compile(
        "编程语言|程序语言|框架|数据库|运行时|\\b(?:programming language|framework|database|runtime|library)\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern APPLICATION_ROLE = Pattern.compile(
        "代码编辑器|文本编辑器|收(?:发)?邮件|发邮件|进行团队协作|团队(?:沟通|协作)(?:软件|工具|应用|平台)|"
            + "\\b(?:code editor|text editor|email (?:client|application|service)|collaboration (?:tool|application))\\b",
        Pattern.CASE_INSENSITIVE);

    private V3EntityRepresentation() {}

    public static String type(String declared, String kindHint, String summary) {
        // A goal about an application is still a goal; an app under construction is still a project.
        // Kind hints refine concrete object categories, not the explicit higher-level semantic role.
        if (!Set.of("technology", "tool", "topic", "other").contains(declared)) return declared;
        String kind = kindHint == null ? "" : kindHint.trim().toUpperCase(Locale.ROOT);
        if (KINDS.containsKey(kind)) return KINDS.get(kind);
        String description = summary == null ? "" : summary;
        if ("technology".equals(declared) && !TECHNOLOGY_CATEGORY.matcher(description).find()
                && APPLICATION_ROLE.matcher(description).find()) return "tool";
        return declared;
    }

    /** Recover an explicitly supplied activity verb only for a descriptive relative-date noun label. */
    public static String eventName(String proposed, String currentMessage) {
        Matcher nominal = Pattern.compile("^(今晚|今早|今天|明晚|明天|后天)的(.+)$").matcher(proposed);
        if (!nominal.matches() || currentMessage == null) return proposed;
        String noun = nominal.group(2);
        Matcher action = Pattern.compile("(看|听|参加|参观|探望|吃|乘坐)(?:一(?:场|次|堂|顿|班|节))?"
            + Pattern.quote(noun)).matcher(currentMessage);
        return action.find() ? nominal.group(1) + action.group(1) + noun : proposed;
    }
}
