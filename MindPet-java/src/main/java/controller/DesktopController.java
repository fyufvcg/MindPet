package controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import config.DynamicLlmConfig;
import jakarta.servlet.http.HttpServletResponse;
import model.ChatResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import service.AiService;
import tool.ToolUserContext;
import util.Logger;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 桌面端 API — AgentPet (Tauri + React) 的前端通过 HTTP 调用这些接口。
 * 所有接口返回 JSON，流式接口返回 NDJSON（每行一个 JSON 对象）。
 */
@RestController
@RequestMapping("/api/desktop")
public class DesktopController {

    private final AiService aiService;
    private final DynamicLlmConfig dynamicLlmConfig;
    private final service.McpManager mcpManager;
    private final config.SkillStore skillStore;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Logger logger;

    @Autowired
    public DesktopController(AiService aiService, DynamicLlmConfig dynamicLlmConfig,
                             service.McpManager mcpManager, config.SkillStore skillStore, Logger logger) {
        this.aiService = aiService;
        this.dynamicLlmConfig = dynamicLlmConfig;
        this.mcpManager = mcpManager;
        this.skillStore = skillStore;
        this.logger = logger;
    }

    /**
     * 流式聊天 — 返回 NDJSON 流，每行一个 JSON 事件。
     * AgentPet 的 backend-api.ts 解析这些事件，转为 IPC 消息发给渲染进程。
     *
     * 请求体：
     * <pre>
     * {
     *   "userId": "desktop-user",
     *   "message": "用户消息文本",
     *   "sessionId": "会话ID",
     *   "history": [{"role":"user","content":"..."}, {"role":"assistant","content":"..."}]
     * }
     * </pre>
     */
    @PostMapping("/chat/stream")
    public void chatStream(@RequestBody Map<String, Object> body, HttpServletResponse response) {
        response.setContentType("application/x-ndjson");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");

        String userId = String.valueOf(body.getOrDefault("userId", "desktop-user"));
        String sessionId = String.valueOf(body.getOrDefault("sessionId", ""));
        String requestId = normalizedRequestId(body.get("messageId"));
        String message = String.valueOf(body.getOrDefault("message", ""));
        String mode = String.valueOf(body.getOrDefault("mode", "chat"));
        int contextRounds = Integer.parseInt(String.valueOf(body.getOrDefault("contextRounds", "6")));
        @SuppressWarnings("unchecked")
        List<String> images = (List<String>) body.getOrDefault("images", List.of());
        @SuppressWarnings("unchecked")
        List<String> activeSkills = (List<String>) body.getOrDefault("activeSkills", List.of());

        ToolUserContext.set(userId, sessionId);
        ToolUserContext.setRequestId(requestId);
        OutputStream out = null;
        try {
            out = response.getOutputStream();
            logger.log("INFO", "[Desktop] 收到请求 userId=" + userId + " sessionId=" + (sessionId.isBlank() ? "(空)" : sessionId) + " 消息长度=" + message.length()
                + (activeSkills.isEmpty() ? "" : " 技能=" + String.join(",", activeSkills)));
            if (message.isBlank() && images.isEmpty()) {
            }

            boolean hasImages = !images.isEmpty();
            logger.log("INFO", "[Desktop] 流式聊天 - userId: " + userId + ", 消息长度: " + message.length()
                + (hasImages ? ", 图片: " + images.size() + "张" : ""));

            final OutputStream streamOut = out;
            java.util.function.Consumer<String> onDelta = delta -> {
                try {
                    writeNdjson(streamOut, "text_delta", delta, null);
                    streamOut.flush();
                } catch (Exception writeError) {
                    throw new IllegalStateException("写入流式响应失败", writeError);
                }
            };

            Set<String> skills = activeSkills.isEmpty() ? Set.of() : new LinkedHashSet<>(activeSkills);
            // 模型产生一个增量就立即写一个 NDJSON 事件。
            ChatResult result;
            if ("summary".equals(mode)) {
                String summary = aiService.chatSimple(message);
                if (summary == null || summary.isBlank()) {
                    throw new IllegalStateException("会话摘要生成失败");
                }
                onDelta.accept(summary);
                result = ChatResult.of(summary, false);
            } else if (hasImages) {
                byte[] imageBytes = java.util.Base64.getDecoder().decode(images.get(0));
                result = aiService.chatWithImageStream(userId,
                    message.isBlank() ? null : message, imageBytes, "image.png", contextRounds, onDelta, skills);
            } else {
                result = aiService.chatStream(userId, message, contextRounds, onDelta, skills);
            }
            String reply = result.reply();

            // 发送完成事件
            writeNdjson(out, "text", reply, null);
            String currentModel = dynamicLlmConfig.hasOverride()
                ? dynamicLlmConfig.getModel() : "doubao-default";
            // 部分兼容 OpenAI 的服务不会在流中返回 usage，交给前端现有逻辑估算。
            if (result.totalTokens() > 0) writeTokenUsage(out, result, currentModel);
            writeGeneratedFiles(out, ToolUserContext.drainGeneratedFiles(requestId));
            writeNdjson(out, "status", "done", null);
            out.flush();

            logger.log("INFO", "[Desktop] 流式回复完成 - userId: " + userId);

        } catch (Exception e) {
            logger.log("ERROR", "[Desktop] 流式聊天失败: " + e.getMessage());
            if (out != null) {
                try {
                    writeNdjson(out, "error", "服务暂时不可用: " + e.getMessage(), null);
                } catch (Exception ignored) {}
            }
        } finally {
            ToolUserContext.drainGeneratedFiles(requestId);
            ToolUserContext.clear();
        }
    }

    /**
     * 非流式聊天 — 直接返回完整 JSON 回复。
     * 用于 AgentPet 的快捷聊天窗口等非流式场景。
     */
    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, Object> body) {
        String userId = String.valueOf(body.getOrDefault("userId", "desktop-user"));
        String sessionId = String.valueOf(body.getOrDefault("sessionId", ""));
        String requestId = normalizedRequestId(body.get("messageId"));
        String message = String.valueOf(body.getOrDefault("message", ""));
        @SuppressWarnings("unchecked")
        List<String> activeSkills = (List<String>) body.getOrDefault("activeSkills", List.of());

        ToolUserContext.set(userId, sessionId);
        ToolUserContext.setRequestId(requestId);
        try {
            if (message.isBlank()) {
                return Map.of("reply", "", "status", "empty_message");
            }

            logger.log("INFO", "[Desktop] 普通聊天 - userId: " + userId);
            Set<String> skills = activeSkills.isEmpty() ? Set.of() : new LinkedHashSet<>(activeSkills);
            ChatResult result = aiService.chat(userId, message, 6, skills);

            List<ToolUserContext.GeneratedFile> generatedFiles = ToolUserContext.drainGeneratedFiles(requestId);
            return Map.of(
                "reply", result.reply(),
                "toolsUsed", result.toolsUsed(),
                "files", generatedFiles,
                "status", "done",
                "promptTokens", result.promptTokens(),
                "completionTokens", result.completionTokens(),
                "totalTokens", result.totalTokens()
            );
        } catch (Exception e) {
            logger.log("ERROR", "[Desktop] 聊天失败: " + e.getMessage());
            return Map.of("reply", "服务暂时不可用: " + e.getMessage(), "status", "error");
        } finally {
            ToolUserContext.drainGeneratedFiles(requestId);
            ToolUserContext.clear();
        }
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "service", "mindpet-desktop-api");
    }

    /**
     * 前端同步 LLM 配置（API Key、模型、Base URL）。
     * 前端设置页修改后调用此接口，后端立即生效。
     */
    @PostMapping("/llm-config")
    public Map<String, Object> syncLlmConfig(@RequestBody Map<String, Object> body) {
        String apiKey = String.valueOf(body.getOrDefault("apiKey", ""));
        String baseUrl = String.valueOf(body.getOrDefault("baseUrl", ""));
        String model = String.valueOf(body.getOrDefault("model", ""));
        String systemPrompt = String.valueOf(body.getOrDefault("systemPrompt", ""));

        dynamicLlmConfig.update(apiKey, baseUrl, model, systemPrompt);
        logger.log("INFO", "[Desktop] LLM 配置已同步: model="
            + (model.isBlank() ? "(未变)" : model)
            + " prompt=" + (systemPrompt.isBlank() ? "(未变)" : systemPrompt.length() + "字符"));
        return Map.of("status", "ok", "message", "配置已同步");
    }

    @GetMapping("/llm-config")
    public Map<String, Object> getLlmConfig() {
        return Map.of(
            "hasApiKey", dynamicLlmConfig.hasOverride(),
            "model", dynamicLlmConfig.getModel() != null ? dynamicLlmConfig.getModel() : "",
            "baseUrl", dynamicLlmConfig.getBaseUrl() != null ? dynamicLlmConfig.getBaseUrl() : "",
            "hasSystemPrompt", dynamicLlmConfig.hasSystemPrompt()
        );
    }

    /**
     * 前端同步 MCP 服务器配置。传入完整服务器列表，后端自动连接并注册工具。
     */
    @PostMapping("/mcp-config")
    public Map<String, Object> syncMcpConfig(@RequestBody Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> servers = (List<Map<String, Object>>) body.getOrDefault("servers", List.of());
            List<service.McpManager.McpServerConfig> configs = new ArrayList<>();
            for (Map<String, Object> s : servers) {
                service.McpManager.McpServerConfig cfg = new service.McpManager.McpServerConfig();
                cfg.id = String.valueOf(s.getOrDefault("id", ""));
                cfg.name = String.valueOf(s.getOrDefault("name", ""));
                cfg.url = String.valueOf(s.getOrDefault("url", ""));
                cfg.apiKey = String.valueOf(s.getOrDefault("apiKey", ""));
                cfg.type = String.valueOf(s.getOrDefault("type", "stream"));
                cfg.enabled = Boolean.parseBoolean(String.valueOf(s.getOrDefault("enabled", "true")));
                Object timeout = s.get("timeout");
                if (timeout instanceof Number) cfg.timeout = ((Number) timeout).intValue();
                if (!cfg.id.isBlank() && !cfg.url.isBlank()) configs.add(cfg);
            }
            mcpManager.syncServers(configs);
            logger.log("INFO", "[Desktop] MCP 配置已同步: " + configs.size() + " 个服务器");
            return Map.of("status", "ok", "servers", configs.size());
        } catch (Exception e) {
            logger.log("ERROR", "[Desktop] MCP 配置同步失败: " + e.getMessage());
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    /**
     * 前端同步技能（SKILL.md 内容）。
     * Map<技能名, SKILL.md内容>，存入后端，每次聊天注入 system prompt。
     */
    @PostMapping("/skills")
    public Map<String, Object> syncSkills(@RequestBody Map<String, String> body) {
        try {
            skillStore.sync(body);
            logger.log("INFO", "[Desktop] 技能已同步: " + skillStore.getAll().size() + " 个");
            return Map.of("status", "ok", "count", skillStore.getAll().size());
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    @GetMapping("/skills")
    public Map<String, Object> getSkills() {
        return Map.of("status", "ok", "skills", skillStore.getAll());
    }

    /**
     * 返回所有可用工具清单（名称 + 描述 + 参数 + 分组），供前端 skill 生成 LLM 参考。
     */
    @GetMapping("/tools/catalog")
    public Map<String, Object> getToolCatalog() {
        try {
            java.util.List<Map<String, Object>> catalog = aiService.getToolCatalog();
            logger.log("INFO", "[Desktop] 工具目录已返回: " + catalog.size() + " 个工具");
            return Map.of("status", "ok", "tools", catalog);
        } catch (Exception e) {
            logger.log("ERROR", "[Desktop] 获取工具目录失败: " + e.getMessage());
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    /**
     * 根据用户自然语言描述生成 SKILL.md。
     * 请求体：{ "description": "用户描述", "skillName": "可选名称" }
     */
    @PostMapping("/skills/generate")
    public Map<String, Object> generateSkill(@RequestBody Map<String, Object> body) {
        String description = String.valueOf(body.getOrDefault("description", ""));
        String skillName = String.valueOf(body.getOrDefault("skillName", ""));
        if (description.isBlank()) {
            return Map.of("status", "error", "message", "描述不能为空");
        }
        try {
            Map<String, Object> result = aiService.generateSkill(description, skillName);
            logger.log("INFO", "[Desktop] Skill 生成: " + result.getOrDefault("name", "?"));
            return result;
        } catch (Exception e) {
            logger.log("ERROR", "[Desktop] Skill 生成失败: " + e.getMessage());
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    // ==================== 工具方法 ====================

    private void writeNdjson(OutputStream out, String type, String content, String message) throws Exception {
        Map<String, Object> event = new java.util.LinkedHashMap<>();
        event.put("type", type);
        if (content != null) event.put("content", content);
        if (message != null) event.put("message", message);
        String json = mapper.writeValueAsString(event) + "\n";
        out.write(json.getBytes(StandardCharsets.UTF_8));
    }

    private void writeGeneratedFiles(OutputStream out, List<ToolUserContext.GeneratedFile> files) throws Exception {
        if (files == null || files.isEmpty()) return;
        Map<String, Object> event = new java.util.LinkedHashMap<>();
        event.put("type", "generated_files");
        event.put("files", files);
        out.write((mapper.writeValueAsString(event) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private String normalizedRequestId(Object value) {
        String requestId = value == null ? "" : String.valueOf(value).trim();
        return requestId.isBlank() || "null".equalsIgnoreCase(requestId) || "undefined".equalsIgnoreCase(requestId)
            ? UUID.randomUUID().toString() : requestId;
    }

    /** 发送 token_usage NDJSON 事件（携带数值型 token 计数） */
    private void writeTokenUsage(OutputStream out, ChatResult result, String model) throws Exception {
        Map<String, Object> event = new java.util.LinkedHashMap<>();
        event.put("type", "token_usage");
        event.put("model", model != null ? model : "unknown");
        event.put("provider", inferProvider(model));
        event.put("promptTokens", result.promptTokens());
        event.put("completionTokens", result.completionTokens());
        event.put("totalTokens", result.totalTokens());
        String json = mapper.writeValueAsString(event) + "\n";
        out.write(json.getBytes(StandardCharsets.UTF_8));
    }

    private String inferProvider(String model) {
        if (model == null) return "unknown";
        String m = model.toLowerCase();
        if (m.contains("deepseek")) return "deepseek";
        if (m.startsWith("ep-")) return "doubao";
        if (m.contains("gpt") || m.contains("o1") || m.contains("o3")) return "openai";
        if (m.contains("gemini")) return "gemini";
        if (m.contains("claude")) return "anthropic";
        return "doubao";
    }
}
