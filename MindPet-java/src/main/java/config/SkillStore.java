package config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 技能存储 — 接收前端同步的技能配置，注入到 LLM system prompt。
 * 每个技能由 name/content/description 组成，description 用于 preCall 轻量路由。
 *
 * JSON 格式：{ "skillName": { "content": "SKILL.md 内容", "description": "一句话描述" } }
 * 兼容旧格式：{ "skillName": "SKILL.md 内容" } — description 自动从 # 标题提取
 */
@Component
public class SkillStore {

    private static final String FILE = "skills-config.json";
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, SkillDef> skills = new ConcurrentHashMap<>();

    public record SkillDef(String content, String description) {}

    @SuppressWarnings("unchecked")
    @PostConstruct
    public void load() {
        try {
            File f = new File(FILE);
            if (f.exists()) {
                Map<String, Object> raw = mapper.readValue(f, Map.class);
                for (var entry : raw.entrySet()) {
                    putSkill(entry.getKey(), entry.getValue());
                }
                System.out.println("[SkillStore] 已加载 " + skills.size() + " 个技能");
            }
        } catch (Exception e) {
            System.err.println("[SkillStore] 加载失败: " + e.getMessage());
        }
    }

    /** 同步：兼容旧格式 {name: content} 和新格式 {name: {content, description}} */
    @SuppressWarnings("unchecked")
    public synchronized void sync(Map<String, ?> incoming) {
        skills.clear();
        if (incoming != null) {
            for (var entry : incoming.entrySet()) {
                putSkill(entry.getKey(), entry.getValue());
            }
        }
        // 持久化为新格式
        Map<String, Map<String, String>> toSave = new LinkedHashMap<>();
        for (var e : skills.entrySet()) {
            toSave.put(e.getKey(), Map.of("content", e.getValue().content(), "description", e.getValue().description()));
        }
        try { mapper.writerWithDefaultPrettyPrinter().writeValue(new File(FILE), toSave); } catch (Exception ignored) {}
        System.out.println("[SkillStore] 已同步 " + skills.size() + " 个技能");
    }

    private void putSkill(String name, Object val) {
        if (val instanceof String s) {
            skills.put(name, new SkillDef(s, extractDescription(s)));
        } else if (val instanceof Map m) {
            String desc = String.valueOf(m.getOrDefault("description", ""));
            String content = String.valueOf(m.getOrDefault("content", ""));
            if (desc.isBlank() || "null".equals(desc)) {
                desc = extractDescription(content);
            }
            skills.put(name, new SkillDef(content, desc));
        }
    }

    /** 从 SKILL.md 内容自动提取一句话描述：优先 # 标题，其次首段 */
    private static String extractDescription(String content) {
        if (content == null || content.isBlank()) return "";
        for (String line : content.split("\\R")) {
            String t = line.trim();
            if (t.startsWith("# ")) return t.substring(2).trim();
            if (t.startsWith("#") && !t.startsWith("##")) return t.substring(1).trim();
        }
        for (String line : content.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("-") || t.startsWith("`")) continue;
            if (t.length() > 80) t = t.substring(0, 80) + "...";
            return t;
        }
        return "";
    }

    /** preCall 用：只返回 name + description 的轻量列表 */
    public String getRoutingPrompt() {
        if (skills.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (var entry : skills.entrySet()) {
            String desc = entry.getValue().description();
            sb.append("- ").append(entry.getKey());
            if (!desc.isBlank()) sb.append("：").append(desc);
            sb.append("\n");
        }
        return sb.toString();
    }

    /** 只注入匹配的 skill 完整内容 */
    public String getPromptFor(Set<String> matchedSkills) {
        if (matchedSkills == null || matchedSkills.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\n## 可用技能\n");
        int count = 0;
        for (String name : matchedSkills) {
            SkillDef def = skills.get(name);
            if (def != null && !def.content().isBlank()) {
                sb.append("\n### ").append(name).append("\n");
                sb.append(def.content()).append("\n");
                count++;
            }
        }
        if (count == 0) return "";
        sb.append("\n请优先遵循上述技能规约，按步骤调用工具完成任务。");
        return sb.toString();
    }

    /** 所有 skill 的名称集合，用于 preCall 返回匹配 */
    public Set<String> getNames() { return Set.copyOf(skills.keySet()); }

    public Map<String, SkillDef> getAll() { return Map.copyOf(skills); }

    /** 兼容旧代码：全部注入 */
    public String getPrompt() {
        if (skills.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\n## 可用技能\n");
        for (var entry : skills.entrySet()) {
            sb.append("\n### ").append(entry.getKey()).append("\n");
            sb.append(entry.getValue().content()).append("\n");
        }
        sb.append("\n请优先遵循上述技能规约，按步骤调用工具完成任务。");
        return sb.toString();
    }
}