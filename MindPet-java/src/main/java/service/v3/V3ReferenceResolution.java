package service.v3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates the narrow, identity-only result of the conditional reference-resolution call.
 * It deliberately cannot create a relation or select an identity that was not supplied by storage.
 */
public final class V3ReferenceResolution {

    private static final List<String> REFERENCE_MARKERS = List.of(
        "这个", "那个", "该", "前者", "后者", "他", "她", "它", "也叫", "同名", "另一个", "两个人",
        "this ", "that ", "former", "latter", "same name", "another ");
    private static final Pattern COMPANY_COLLEAGUE = Pattern.compile(
        "(?:我)?公司(?:有个|的)?同事(?:叫|名叫)([\\p{IsHan}]{2,4})");
    private static final Pattern PERSONAL_RELATION = Pattern.compile(
        "我(邻居|同事|朋友|同学|客户|导师|上司)(?:也)?(?:叫|名叫)([\\p{IsHan}]{2,4})");

    private V3ReferenceResolution() {}

    public record Candidate(String id, String canonicalName, String type, String summary) {}
    public record Binding(String surface, Candidate candidate) {}
    public record CurrentEntity(String surface, String canonicalName, String type) {}

    public record Plan(
            List<Binding> bindings,
            List<CurrentEntity> currentEntities,
            Set<String> contextOnlyTerms) {
        public static Plan empty() {
            return new Plan(List.of(), List.of(), Set.of());
        }

        public boolean isEmpty() {
            return bindings.isEmpty() && currentEntities.isEmpty() && contextOnlyTerms.isEmpty();
        }

        public String rewriteEndpoint(String value) {
            String normalized = normalize(value);
            for (Binding binding : bindings) {
                if (normalize(binding.surface()).equals(normalized)) {
                    return binding.candidate().canonicalName();
                }
            }
            return value;
        }

        /**
         * A resolution pass may establish one qualified current identity while extraction emits only its
         * bare name. Apply that one-to-one constraint before turn-local canonicalization. Ambiguous bare
         * names remain unchanged rather than being guessed.
         */
        public String rewriteCurrentCanonical(String value) {
            String base = baseName(value);
            CurrentEntity match = null;
            for (CurrentEntity entity : currentEntities) {
                if (!baseName(entity.canonicalName()).equals(base)) continue;
                if (match != null && !normalize(match.canonicalName()).equals(normalize(entity.canonicalName()))) {
                    return value;
                }
                match = entity;
            }
            return match == null ? value : match.canonicalName();
        }

        public boolean suppresses(String value) {
            return contextOnlyTerms.contains(normalize(value));
        }

        public String extractionContext() {
            if (isEmpty()) return "";
            StringBuilder out = new StringBuilder("\n\nREFERENCE-RESOLUTION OUTCOME (identity context only; not evidence):\n");
            for (Binding binding : bindings) {
                out.append("- Historical reference `").append(binding.surface())
                    .append("` is the existing entity `").append(binding.candidate().canonicalName())
                    .append("` (type ").append(binding.candidate().type()).append(").\n");
            }
            for (CurrentEntity entity : currentEntities) {
                out.append("- Current explicit mention `").append(entity.surface())
                    .append("` must remain the distinct entity `").append(entity.canonicalName())
                    .append("` (type ").append(entity.type()).append(").\n");
            }
            if (!contextOnlyTerms.isEmpty()) {
                out.append("- Context-only role/detail terms, not standalone entities: ")
                    .append(String.join(", ", contextOnlyTerms)).append(".\n");
            }
            out.append("Use these exact canonical names when relevant. Include a referenced historical entity "
                + "in entities if it is a relation endpoint. Do not infer or copy any historical assertion.");
            return out.toString();
        }
    }

    public static boolean needsResolution(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return false;
        // A demonstrative time window is not an entity reference. Do not let an identity-only
        // call reinterpret the project's temporal work context as a person role or duty.
        String lower = userMessage.toLowerCase(Locale.ROOT).replaceAll(
            "(?:这个|那个|该|本)(?:季度|星期|周|月份?|年|时段|阶段)|\\b(?:this|that)\\s+(?:quarter|week|month|year)\\b", " ");
        return REFERENCE_MARKERS.stream().anyMatch(lower::contains);
    }

    /** Resolve a typed demonstrative only when exactly one supplied historical identity fits. */
    public static Plan completeTypedReferences(Plan plan, String message, List<Candidate> candidates) {
        List<Binding> bindings = new ArrayList<>(plan.bindings());
        Map<String, String> types = Map.of(
            "这个项目", "project", "该项目", "project", "此项目", "project",
            "这家公司", "organization", "该公司", "organization", "这个组织", "organization",
            "这个工具", "tool", "该工具", "tool");
        for (Map.Entry<String, String> reference : types.entrySet()) {
            if (!occursIn(message, reference.getKey())
                    || bindings.stream().anyMatch(b -> normalize(b.surface()).equals(normalize(reference.getKey())))) continue;
            List<Candidate> compatible = candidates.stream().filter(c -> reference.getValue().equals(c.type())).toList();
            if (compatible.size() == 1) bindings.add(new Binding(reference.getKey(), compatible.get(0)));
        }
        return new Plan(List.copyOf(bindings), plan.currentEntities(), plan.contextOnlyTerms());
    }

    /** Accept only bindings to supplied candidates and current identities explicitly surfaced in the message. */
    public static Plan parse(
            ObjectMapper mapper, String raw, String userMessage, List<Candidate> candidates) throws Exception {
        if (raw == null || raw.isBlank() || userMessage == null || userMessage.isBlank()) return Plan.empty();
        Map<String, Candidate> candidateById = new LinkedHashMap<>();
        for (Candidate candidate : candidates) candidateById.put(candidate.id(), candidate);
        JsonNode root = mapper.readTree(jsonObject(raw));

        List<Binding> bindings = new ArrayList<>();
        Set<String> boundSurfaces = new LinkedHashSet<>();
        for (JsonNode node : array(root, "bindings")) {
            String surface = clean(node.path("surface").asText());
            Candidate candidate = candidateById.get(clean(node.path("candidateId").asText()));
            if (candidate != null && occursIn(userMessage, surface) && boundSurfaces.add(normalize(surface))) {
                bindings.add(new Binding(surface, candidate));
            }
        }

        List<CurrentEntity> current = new ArrayList<>();
        Set<String> canonicalNames = new LinkedHashSet<>();
        for (JsonNode node : array(root, "currentEntities")) {
            String surface = clean(node.path("surface").asText());
            String canonical = clean(node.path("canonicalName").asText());
            String type = clean(node.path("type").asText("person")).toLowerCase(Locale.ROOT);
            if (!type.equals("person") || !occursIn(userMessage, surface) || canonical.isBlank()
                    || canonical.equalsIgnoreCase(surface) || !canonicalNames.add(normalize(canonical))) continue;
            current.add(new CurrentEntity(surface, canonical, type));
        }

        Set<String> contextOnly = new LinkedHashSet<>();
        for (JsonNode node : array(root, "contextOnlyTerms")) {
            String value = clean(node.asText());
            if (occursIn(userMessage, value)) contextOnly.add(normalize(value));
        }
        addExplicitRelationshipIdentities(userMessage, current, canonicalNames);
        return new Plan(List.copyOf(bindings), List.copyOf(current), Set.copyOf(contextOnly));
    }

    private static List<JsonNode> array(JsonNode root, String key) {
        if (!root.path(key).isArray()) return List.of();
        List<JsonNode> values = new ArrayList<>();
        root.path(key).forEach(values::add);
        return values;
    }

    private static boolean occursIn(String text, String phrase) {
        return !phrase.isBlank() && text.toLowerCase(Locale.ROOT).contains(phrase.toLowerCase(Locale.ROOT));
    }

    private static String jsonObject(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int closing = trimmed.lastIndexOf("```");
            if (firstNewline >= 0 && closing > firstNewline) trimmed = trimmed.substring(firstNewline + 1, closing).trim();
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end < start) throw new IllegalArgumentException("Reference resolver returned no JSON object");
        return trimmed.substring(start, end + 1);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private static String normalize(String value) {
        return clean(value).toLowerCase(Locale.ROOT);
    }

    private static String baseName(String value) {
        String normalized = normalize(value);
        int ascii = normalized.indexOf('(');
        int fullWidth = normalized.indexOf('（');
        int start = ascii < 0 ? fullWidth : fullWidth < 0 ? ascii : Math.min(ascii, fullWidth);
        return start > 0 ? normalized.substring(0, start).trim() : normalized;
    }

    /**
     * This is not an entity extractor: it merely gives an explicitly named person a stable label when
     * the same sentence states their direct social or organisational relationship to the user. It runs
     * only in the already-conditional reference path and never selects a historical entity.
     */
    private static void addExplicitRelationshipIdentities(
            String userMessage, List<CurrentEntity> current, Set<String> canonicalNames) {
        addRelationshipMatches(COMPANY_COLLEAGUE.matcher(userMessage), "公司同事", current, canonicalNames);
        Matcher matcher = PERSONAL_RELATION.matcher(userMessage);
        while (matcher.find()) {
            String relationship = clean(matcher.group(1));
            String name = clean(matcher.group(2));
            addCurrentIdentity(name, name + "(" + relationship + ")", current, canonicalNames);
        }
    }

    private static void addRelationshipMatches(
            Matcher matcher, String relationship, List<CurrentEntity> current, Set<String> canonicalNames) {
        while (matcher.find()) {
            String name = clean(matcher.group(1));
            addCurrentIdentity(name, name + "(" + relationship + ")", current, canonicalNames);
        }
    }

    private static void addCurrentIdentity(
            String surface, String canonical, List<CurrentEntity> current, Set<String> canonicalNames) {
        if (surface.isBlank()) return;
        String base = baseName(surface);
        // An explicit relationship label is narrower and more stable than a model-generated job-detail
        // label for the same bare name (for example, neighbour vs neighbour-photographer).
        current.removeIf(existing -> baseName(existing.canonicalName()).equals(base));
        canonicalNames.clear();
        for (CurrentEntity existing : current) canonicalNames.add(normalize(existing.canonicalName()));
        if (!canonicalNames.add(normalize(canonical))) return;
        current.add(new CurrentEntity(surface, canonical, "person"));
    }
}
