package service.v3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/** Redacts secret values before storage and embedding. Does not retain user values. */
public final class SecretSanitizer {
    public static final String REDACTED = "[REDACTED_SECRET]";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String LABEL = "(?:api[\\s_-]*key|access[\\s_-]*token|refresh[\\s_-]*token|password|passwd|passphrase|cookie|口令|密码|密钥|验证码|短信码|访问令牌|身份证号?|银行卡号?|卡号)";
    private static final Pattern NAMED = Pattern.compile("(?iu)" + LABEL
        + "(?:\\s*(?:是|为|叫|等于))?\\s*[：:=]?\\s*[\"'“‘]?([^\\s\"'”’，。；;！？!?{}\\[\\],:：=]{2,})");
    private static final Pattern TOKEN = Pattern.compile("(?iu)(?:\\bsk-[a-z0-9_-]{12,}|\\bbearer\\s+[a-z0-9._-]{12,}|(?<!\\d)\\d{17}[0-9X](?!\\d)|(?<!\\d)(?:\\d[ -]?){15,18}\\d(?!\\d))");
    private static final Pattern KEY = Pattern.compile("(?iu)^" + LABEL + "$");
    private SecretSanitizer() {}

    public static List<String> values(String text) {
        if (text == null || text.isBlank()) return List.of();
        var values = new LinkedHashSet<String>();
        for (var span : V3SensitiveAccount.identifiers(text)) values.add(span.value());
        var named = NAMED.matcher(text);
        while (named.find()) values.add(named.group(1));
        var token = TOKEN.matcher(text);
        while (token.find()) values.add(token.group());
        if (text.stripLeading().startsWith("{") || text.stripLeading().startsWith("[")) {
            try { collect(JSON.readTree(text), values); } catch (Exception ignored) { /* Treat malformed JSON as text. */ }
        }
        values.remove(REDACTED); values.remove(SensitivePersistenceGuard.REDACTED);
        return List.copyOf(values);
    }

    private static void collect(JsonNode node, java.util.Set<String> values) {
        if (node == null) return;
        if (node.isObject()) node.fields().forEachRemaining(field -> {
            if (KEY.matcher(field.getKey()).matches() && field.getValue().isValueNode()) values.add(field.getValue().asText());
            collect(field.getValue(), values);
        });
        else if (node.isArray()) node.forEach(child -> collect(child, values));
        else if (node.isTextual()) {
            String text = node.asText();
            var named = NAMED.matcher(text); while (named.find()) values.add(named.group(1));
            var token = TOKEN.matcher(text); while (token.find()) values.add(token.group());
            for (var span : V3SensitiveAccount.identifiers(text)) values.add(span.value());
        }
    }

    public static String sanitize(String text) { return sanitize(text, List.of()); }
    public static String sanitize(String text, List<String> knownValues) {
        if (text == null) return null;
        List<String> values = new ArrayList<>(knownValues); values.addAll(values(text));
        if (text.stripLeading().startsWith("{") || text.stripLeading().startsWith("[")) {
            try {
                JsonNode node = JSON.readTree(text);
                sanitizeNode(node, values);
                return JSON.writeValueAsString(node);
            } catch (Exception ignored) { /* Redact textual payload without corrupting SQL. */ }
        }
        return replace(text, values);
    }

    private static void sanitizeNode(JsonNode node, List<String> values) {
        if (node instanceof ObjectNode object) {
            List<String> names = new ArrayList<>(); object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                JsonNode child = object.get(name);
                if (KEY.matcher(name).matches()) object.put(name, REDACTED);
                else if (child.isTextual()) object.put(name, replace(child.asText(), values));
                else sanitizeNode(child, values);
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                JsonNode child = array.get(i);
                if (child.isTextual()) array.set(i, JSON.getNodeFactory().textNode(replace(child.asText(), values)));
                else sanitizeNode(child, values);
            }
        }
    }

    private static String replace(String text, List<String> values) {
        String clean = text;
        for (String value : values.stream().filter(v -> v != null && !v.isBlank()).distinct()
                .sorted(java.util.Comparator.comparingInt(String::length).reversed()).toList()) {
            clean = Pattern.compile(Pattern.quote(value), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                .matcher(clean).replaceAll(REDACTED);
        }
        return clean;
    }
}
