package config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Unified skill catalog. Only descriptions are resident; bodies and resources load on demand. */
@Component
public class SkillStore {
    private static final String FILE = "skills-config.json";
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile Map<String, SkillDef> skills = Map.of();
    public record SkillDef(String content, String description, String source, String rootPath,
                           Map<String, String> resources, String version) {}

    @PostConstruct public void load() {
        Map<String, Object> incoming = new LinkedHashMap<>();
        try {
            File file = new File(FILE);
            if (file.exists()) incoming.putAll(mapper.readValue(file, Map.class));
        } catch (Exception e) { System.err.println("[SkillStore] 读取已安装技能失败: " + e.getClass().getSimpleName()); }
        replace(incoming);
    }

    public synchronized void sync(Map<String, ?> incoming) {
        Map<String, ?> installed = incoming == null ? Map.of() : incoming;
        replace(installed);
        try { mapper.writerWithDefaultPrettyPrinter().writeValue(new File(FILE), installed); }
        catch (Exception e) { throw new IllegalStateException("技能目录保存失败", e); }
    }

    private void replace(Map<String, ?> incoming) {
        Map<String, SkillDef> next = new TreeMap<>();
        addBuiltin(next, "local-files", "本地资料处理：查找、读取、分类和整理文件，保留来源并检查结果。");
        addBuiltin(next, "office", "办公文档处理：制作和修改 Word、Excel、PDF、PPTX，按格式加载操作说明。");
        addBuiltin(next, "web-research", "联网资料整理：适用于最新、易变信息或用户要求查证的任务，查找来源并整理证据。");
        incoming.forEach((name, value) -> {
            if (name == null || name.isBlank() || name.length() > 240) return;
            Map<?, ?> metadata = value instanceof Map<?, ?> map ? map : Map.of();
            String content = value instanceof String text ? text : string(metadata.get("content"));
            if (content.isBlank()) return;
            String description = string(metadata.get("description"));
            if (description.isBlank()) description = extractDescription(content);
            Map<String, String> resources = new TreeMap<>();
            if (metadata.get("resources") instanceof Map<?, ?> refs) refs.forEach((key, body) -> {
                String path = key.toString().replace('\\', '/');
                if (!path.startsWith("/") && !Arrays.asList(path.split("/")).contains(".."))
                    resources.put(path, Objects.toString(body, ""));
            });
            String id = name.startsWith("user/") ? name : "user/" + name;
            next.put(id, definition(content, description, "user", string(metadata.get("rootPath")), resources));
        });
        skills = Collections.unmodifiableMap(next);
    }

    private void addBuiltin(Map<String, SkillDef> target, String name, String description) {
        try {
            String body = new ClassPathResource("skills/" + name + "/SKILL.md").getContentAsString(StandardCharsets.UTF_8);
            target.put("builtin/" + name, definition(body, description, "builtin", "", Map.of()));
        } catch (Exception e) { throw new IllegalStateException("缺少内置技能: " + name, e); }
    }
    private SkillDef definition(String body, String desc, String source, String root, Map<String, String> refs) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((body + desc + root + refs).getBytes(StandardCharsets.UTF_8));
            return new SkillDef(body, desc.substring(0, Math.min(320, desc.length())), source, root,
                Map.copyOf(refs), HexFormat.of().formatHex(digest).substring(0, 16));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static String string(Object value) { return Objects.toString(value, ""); }
    private static String extractDescription(String body) {
        for (String line : body.split("\\R")) {
            String text = line.trim();
            if (text.startsWith("description:")) return text.substring(12).trim().replaceAll("^[\"']|[\"']$", "");
        }
        for (String line : body.split("\\R")) {
            String text = line.trim();
            if (!text.isBlank() && !text.equals("---") && !text.startsWith("name:"))
                return text.replaceFirst("^#+\\s*", "");
        }
        return "用户安装的技能";
    }

    public SkillDef get(String id) { return skills.get(id); }
    public Set<String> getNames() { return skills.keySet(); }
    public Map<String, SkillDef> getAll() { return skills; }
    public List<Map<String, Object>> catalog(String query, int offset, int limit) {
        String text = Objects.toString(query, "").toLowerCase(Locale.ROOT).trim();
        return skills.entrySet().stream().filter(e -> service.agent.CapabilitySearch.score(text, e.getKey() + " " + e.getValue().description()) > 0)
            .sorted(Comparator.<Map.Entry<String, SkillDef>>comparingInt(e ->
                service.agent.CapabilitySearch.score(text, e.getKey() + " " + e.getValue().description())).reversed()
                .thenComparing(Map.Entry::getKey))
            .skip(Math.max(0, offset)).limit(Math.max(1, Math.min(50, limit))).map(e -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", e.getKey()); row.put("description", e.getValue().description());
                row.put("source", e.getValue().source()); row.put("version", e.getValue().version()); return row;
            }).toList();
    }
    public String getRoutingPrompt() {
        StringBuilder out = new StringBuilder("## Skill 能力目录\n安装的 Skill 自动参与发现，无需开关。用 agent_discover_skills 搜索或分页查看，用 agent_load_skill 加载正文。\n");
        int budget = 5000;
        for (var entry : skills.entrySet()) {
            String line = "- " + entry.getKey() + ": " + entry.getValue().description() + "\n";
            if (line.length() > budget) { out.append("其余已安装 Skill 请通过 agent_discover_skills 查找。\n"); break; }
            out.append(line); budget -= line.length();
        }
        return out.toString();
    }
    /** Legacy callers receive an overview, never all skill bodies. */
    public String getPromptFor(Set<String> ignored) { return getRoutingPrompt(); }
    public String getPrompt() { return getRoutingPrompt(); }
}
