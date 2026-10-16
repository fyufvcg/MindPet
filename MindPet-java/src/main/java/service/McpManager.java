package service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Service;
import util.Logger;
import service.agent.ToolExposurePolicy;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通用 MCP 管理器 — 接收前端配置的任意 MCP 服务器，自动发现工具并注册为 Spring AI Tool。
 */
@Service
public class McpManager implements ToolCallbackProvider {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Logger logger;
    private final Map<String, McpConnection> connections = new ConcurrentHashMap<>();
    private final Map<String, String> configuredNames = new ConcurrentHashMap<>();

    public McpManager(Logger logger) {
        this.logger = logger;
    }

    /** 前端同步 MCP 配置 */
    public synchronized void syncServers(List<McpServerConfig> configs) {
        configuredNames.clear();
        Set<String> newIds = new HashSet<>();
        for (McpServerConfig cfg : configs.stream().sorted(Comparator.comparing(c -> !"DESKTOP_BUILTIN".equals(c.source))).toList()) {
            if (!cfg.enabled || cfg.url == null || cfg.url.isBlank()) continue;
            configuredNames.put(cfg.id, Objects.toString(cfg.name, cfg.id));
            newIds.add(cfg.id);
            McpConnection existing = connections.get(cfg.id);
            if (existing != null && existing.sameConfig(cfg)) continue;
            if (existing != null) closeConnection(cfg.id);
            connectAndRegister(cfg);
        }
        for (String id : new ArrayList<>(connections.keySet())) {
            if (!newIds.contains(id)) closeConnection(id);
        }
    }

    /** Refresh live tool schemas instead of returning the connection-time snapshot. */
    public synchronized void refreshToolCatalog() {
        List<String> failures = new ArrayList<>();
        for (McpConnection connection : new ArrayList<>(connections.values())) {
            McpServerConfig cfg = connection.config;
            try {
                List<ToolCallback> callbacks = new ArrayList<>();
                for (ToolDef tool : listTools(cfg)) {
                    String name = sanitizeName(cfg, tool.name);
                    if (!"USER_MCP".equals(cfg.source) && !ToolExposurePolicy.allows(name)) continue;
                    String schema = tool.inputSchema != null ? mapper.writeValueAsString(tool.inputSchema) : "{}";
                    callbacks.add(new McpToolCallback(name, tool.description, schema, tool.name, cfg));
                }
                connections.put(cfg.id, new McpConnection(cfg, callbacks));
            } catch (Exception e) {
                failures.add(Objects.toString(cfg.name, cfg.id));
            }
        }
        if (!failures.isEmpty()) throw new IllegalStateException("部分服务目录同步失败：" + String.join("、", failures));
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        List<ToolCallback> list = new ArrayList<>();
        for (McpConnection conn : connections.values()) list.addAll(conn.toolCallbacks);
        return list.toArray(new ToolCallback[0]);
    }

    public ToolCallback[] callbacksFor(Set<String> selectedServers) {
        return connections.values().stream()
            .filter(conn -> "DESKTOP_BUILTIN".equals(conn.config.source) || selectedServers.contains(conn.config.id))
            .flatMap(conn -> conn.toolCallbacks.stream()).toArray(ToolCallback[]::new);
    }

    public boolean isManaged(ToolCallback callback) { return callback instanceof McpToolCallback; }
    public boolean isBuiltin(ToolCallback callback) {
        return callback instanceof McpToolCallback tool && "DESKTOP_BUILTIN".equals(tool.config.source);
    }
    public int serverToolCount(ToolCallback callback) {
        if (!(callback instanceof McpToolCallback tool)) return 0;
        McpConnection conn = connections.get(tool.config.id);
        return conn == null ? 0 : conn.toolCallbacks.size();
    }
    public String sourceOf(ToolCallback callback) {
        return isBuiltin(callback) ? "desktop" : isManaged(callback) ? "mcp" : "java";
    }
    public String serverIdOf(ToolCallback callback) {
        return callback instanceof McpToolCallback tool ? tool.config.id : "";
    }
    public String scopeSummary(Set<String> selected) {
        if (selected.isEmpty()) return "本次任务未选择外部 MCP 服务。";
        StringBuilder summary = new StringBuilder("本次任务已选择的外部 MCP（大型服务可用 agent_discover_tools 按服务 id 查找工具）：\n");
        for (String id : selected) {
            McpConnection connection = connections.get(id);
            summary.append("- ").append(id).append(" ").append(configuredNames.getOrDefault(id, "未配置的服务"))
                .append(connection == null ? "：未连接或连接失败，请说明具体限制。" : "：可用工具 " + connection.toolCallbacks.size()).append("\n");
        }
        return summary.toString();
    }

    // ==================== 连接 & 工具发现 ====================

    private void connectAndRegister(McpServerConfig cfg) {
        try {
            logger.log("INFO", "[MCP] 正在连接 " + cfg.name + " (" + cfg.url + ")");
            if ("DESKTOP_BUILTIN".equals(cfg.source) && !initAndCheck(cfg)) {
                logger.log("WARN", "[MCP] " + cfg.name + " 初始化失败");
                return;
            }
            List<ToolDef> tools = listTools(cfg);
            // A legacy Mi Home server may have an opaque id/name. Its device schema identifies
            // the whole vertical service, so login/get_statistics cannot leak through discovery.
            Set<String> rawNames = new HashSet<>();
            for (ToolDef tool : tools) rawNames.add(tool.name);
            if (!"USER_MCP".equals(cfg.source) && rawNames.contains("get_device_spec") && rawNames.contains("list_devices")) {
                logger.log("INFO", "[MCP] 跳过已移除的米家专用工具服务");
                return;
            }
            if (tools.isEmpty()) {
                logger.log("WARN", "[MCP] " + cfg.name + " 没有工具");
                return;
            }
            List<ToolCallback> cbs = new ArrayList<>();
            for (ToolDef tool : tools) {
                String name = sanitizeName(cfg, tool.name);
                if (!"USER_MCP".equals(cfg.source) && !ToolExposurePolicy.allows(name)) continue;
                String schema = tool.inputSchema != null ? mapper.writeValueAsString(tool.inputSchema) : "{}";
                cbs.add(new McpToolCallback(name, tool.description, schema, tool.name, cfg));
            }
            connections.put(cfg.id, new McpConnection(cfg, cbs));
            logger.log("INFO", "[MCP] " + cfg.name + " 已连接，" + cbs.size() + " 个通用工具");
        } catch (Exception e) {
            logger.log("ERROR", "[MCP] " + cfg.name + " 异常: " + e.getMessage());
        }
    }

    private void closeConnection(String id) {
        McpConnection c = connections.remove(id);
        if (c != null) logger.log("INFO", "[MCP] 断开: " + c.config.name);
    }

    // ==================== MCP 协议 ====================

    private boolean initAndCheck(McpServerConfig cfg) {
        try {
            // initialize
            ObjectNode params = mapper.createObjectNode();
            params.put("protocolVersion", "2025-03-26");
            params.set("capabilities", mapper.createObjectNode());
            ObjectNode ci = mapper.createObjectNode();
            ci.put("name", "mindpet"); ci.put("version", "1.0.0");
            params.set("clientInfo", ci);

            ObjectNode req = mapper.createObjectNode();
            req.put("jsonrpc", "2.0"); req.put("method", "initialize"); req.put("id", 1);
            req.set("params", params);

            JsonNode resp = sendJsonRpc(cfg, req);
            if (resp == null || resp.has("error")) return false;

            // initialized 通知（MCP 协议要求）
            ObjectNode not = mapper.createObjectNode();
            not.put("jsonrpc", "2.0"); not.put("method", "notifications/initialized");
            sendJsonRpc(cfg, not);

            return true;
        } catch (Exception e) {
            logger.log("ERROR", "[MCP] init " + cfg.name + ": " + e.getMessage());
            return false;
        }
    }

    private List<ToolDef> listTools(McpServerConfig cfg) {
        try {
            ObjectNode req = mapper.createObjectNode();
            req.put("jsonrpc", "2.0"); req.put("method", "tools/list"); req.put("id", 2);

            McpServerConfig transport = cfg;
            if (!"DESKTOP_BUILTIN".equals(cfg.source)) {
                transport = desktopBridge();
                req.put("method", "mindpet/mcp-list");
                req.set("params", mapper.valueToTree(Map.of("serverId", cfg.id)));
            }
            JsonNode resp = sendJsonRpc(transport, req);
            if (resp == null || !resp.has("result")) throw new IllegalStateException("tools/list 请求失败");
            JsonNode arr = resp.get("result").get("tools");
            if (arr == null || !arr.isArray()) throw new IllegalStateException("tools/list 响应无效");

            List<ToolDef> list = new ArrayList<>();
            for (JsonNode t : arr) {
                String name = t.path("name").asText("");
                String desc = t.path("description").asText("");
                Map<String, Object> schema = mapper.convertValue(t.get("inputSchema"), Map.class);
                if (!name.isBlank()) list.add(new ToolDef(name, desc, schema));
            }
            return list;
        } catch (Exception e) {
            logger.log("ERROR", "[MCP] listTools " + cfg.name + ": " + e.getMessage());
            throw new IllegalStateException("无法读取服务工具目录", e);
        }
    }

    private String callTool(McpServerConfig cfg, String toolName, Map<String, Object> arguments) {
        try {
            ObjectNode params = mapper.createObjectNode();
            params.put("name", toolName);
            params.set("arguments", mapper.valueToTree(arguments != null ? arguments : Map.of()));
            {
                Map<String, String> execution = tool.ToolUserContext.getExecution();
                Map<String, String> context = new LinkedHashMap<>(execution);
                context.put("sessionId", tool.ToolUserContext.getSessionId());
                context.put("messageId", Objects.toString(tool.ToolUserContext.getRequestId(), ""));
                params.set("_meta", mapper.valueToTree(Map.of("mindpet/context", context)));
            }

            ObjectNode req = mapper.createObjectNode();
            req.put("jsonrpc", "2.0"); req.put("method", "tools/call"); req.put("id", 3);
            req.set("params", params);

            McpServerConfig transport = cfg;
            if (!"DESKTOP_BUILTIN".equals(cfg.source)) {
                transport = desktopBridge(); req.put("method", "mindpet/mcp-call"); params.put("serverId", cfg.id);
            }
            JsonNode resp = sendJsonRpc(transport, req);
            if (resp == null) return toolError("MCP_TRANSPORT_ERROR", "MCP 请求失败或超时，操作结果可能未知。写操作请先检查实际状态，不要直接重试。");
            if (resp.has("error")) return toolError("MCP_PROTOCOL_ERROR", resp.get("error").toString());

            JsonNode result = resp.path("result");
            ObjectNode envelope = mapper.createObjectNode();
            envelope.put("mindpetToolResult", true);
            envelope.put("success", !result.path("isError").asBoolean(false));
            envelope.put("outcome", result.path("structuredContent").path("outcome").asText("confirmed"));
            envelope.put("content", result.path("content").findValuesAsText("text").stream().collect(java.util.stream.Collectors.joining("\n")));
            if (result.has("structuredContent")) envelope.set("state", result.path("structuredContent").path("state"));
            var images = envelope.putArray("images");
            var resources = envelope.putArray("resources");
            for (JsonNode item : result.path("content")) {
                if ("image".equals(item.path("type").asText())) images.add(item);
                else if (!"text".equals(item.path("type").asText())) resources.add(item);
            }
            return envelope.toString();
        } catch (Exception e) {
            return toolError("MCP_TRANSPORT_ERROR", "响应无法确认；请核实实际结果，勿直接重试。");
        }
    }

    // ==================== HTTP ====================

    private JsonNode sendJsonRpc(McpServerConfig cfg, ObjectNode request) {
        HttpURLConnection conn = null;
        try {
            String body = mapper.writeValueAsString(request);
            String method = request.path("method").asText("?");

            conn = (HttpURLConnection) URI.create(cfg.url).toURL().openConnection();
            HttpURLConnection cancellableConnection = conn;
            if ("tools/call".equals(method) || "mindpet/mcp-call".equals(method)) tool.ToolUserContext.registerCancellation(cancellableConnection::disconnect);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(cfg.timeout > 0 ? cfg.timeout * 1000 : 30000);
            if ("DESKTOP_BUILTIN".equals(cfg.source) && ("tools/call".equals(method) || "mindpet/mcp-call".equals(method))) {
                long deadline = Long.parseLong(tool.ToolUserContext.getExecution().getOrDefault("deadline", Long.toString(System.currentTimeMillis() + 600_000)));
                conn.setReadTimeout((int)Math.max(1, Math.min(Integer.MAX_VALUE, deadline - System.currentTimeMillis())));
            }
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json, text/event-stream");
            if (cfg.apiKey != null && !cfg.apiKey.isBlank())
                conn.setRequestProperty("Authorization", "Bearer " + cfg.apiKey);
            if (cfg.sessionId != null && !cfg.sessionId.isBlank())
                conn.setRequestProperty("Mcp-Session-Id", cfg.sessionId);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            String newSessionId = conn.getHeaderField("Mcp-Session-Id");
            if (newSessionId != null && !newSessionId.isBlank()) {
                cfg.sessionId = newSessionId;
            }
            String ct = conn.getContentType();
            logger.log("DEBUG", "[MCP] ← " + cfg.name + " " + method + " HTTP " + code + " ct=" + ct);

            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) { logger.log("ERROR", "[MCP] " + cfg.name + " HTTP " + code + " body=null"); return null; }

            String raw = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            logger.log("DEBUG", "[MCP] ← " + cfg.name + " body length=" + raw.length());

            if (code >= 400) { logger.log("ERROR", "[MCP] " + cfg.name + " HTTP " + code + ": " + raw.substring(0, Math.min(200, raw.length()))); return null; }

            // SSE 提取 data:
            if (ct != null && ct.contains("text/event-stream")) {
                StringBuilder sb = new StringBuilder();
                for (String line : raw.split("\n"))
                    if (line.startsWith("data:")) sb.append(line.substring(5).trim());
                raw = sb.toString();
                if (raw.isEmpty()) { logger.log("WARN", "[MCP] SSE body 无 data"); return null; }
            }

            JsonNode result = mapper.readTree(raw);
            if (result.has("error"))
                logger.log("ERROR", "[MCP] " + cfg.name + " rpc error: " + result.get("error"));
            return result;
        } catch (Exception e) {
            logger.log("ERROR", "[MCP] " + cfg.name + " 请求异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String toolError(String code, String message) {
        try { return mapper.writeValueAsString(Map.of("success", false, "code", code, "outcome", code.equals("MCP_TRANSPORT_ERROR") ? "unknown" : "not_executed", "message", Objects.toString(message, ""))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private McpServerConfig desktopBridge() {
        return connections.values().stream().filter(c -> "DESKTOP_BUILTIN".equals(c.config.source))
            .map(c -> c.config).findFirst().orElseThrow(() -> new IllegalStateException("Desktop 工具桥尚未连接"));
    }

    public void finishDesktopTask(String sessionId, String messageId) {
        for (McpConnection connection : connections.values()) {
            if (!"DESKTOP_BUILTIN".equals(connection.config.source)) continue;
            ObjectNode request = mapper.createObjectNode();
            request.put("jsonrpc", "2.0"); request.put("method", "mindpet/task-finished"); request.put("id", UUID.randomUUID().toString());
            request.set("params", mapper.valueToTree(Map.of("sessionId", sessionId, "messageId", messageId)));
            sendJsonRpc(connection.config, request);
        }
    }

    // ==================== helper ====================

    private String sanitizeName(McpServerConfig cfg, String toolName) {
        String serverId = cfg.id;
        // Keep the builtin names readable; external names have an exact, bounded identity.
        if ("DESKTOP_BUILTIN".equals(cfg.source)) return serverId.replaceAll("[^a-zA-Z0-9_]", "_") + "__" + toolName.replaceAll("[^a-zA-Z0-9_]", "_");
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(mapper.writeValueAsBytes(List.of(serverId, toolName)));
            return "mcp_" + HexFormat.of().formatHex(digest).substring(0, 48);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    // ==================== 内部类 ====================

    public static class McpServerConfig {
        public String id, name, url, apiKey, type = "stream";
        public String source = "USER_MCP";
        public boolean enabled = true;
        public int timeout = 60;
        public String sessionId; // MCP Streamable HTTP session
    }

    private record ToolDef(String name, String description, Map<String, Object> inputSchema) {}

    private static class McpConnection {
        final McpServerConfig config;
        final List<ToolCallback> toolCallbacks;
        McpConnection(McpServerConfig c, List<ToolCallback> t) { config = c; toolCallbacks = t; }
        boolean sameConfig(McpServerConfig o) {
            return Objects.equals(config.url, o.url) && Objects.equals(config.apiKey, o.apiKey)
                && Objects.equals(config.type, o.type) && Objects.equals(config.source, o.source)
                && config.timeout == o.timeout;
        }
    }

    private class McpToolCallback implements ToolCallback {
        private final ToolDefinition def;
        private final String mcpName;
        private final McpServerConfig config;

        McpToolCallback(String springName, String desc, String inputSchema, String mcpName, McpServerConfig config) {
            this.def = ToolDefinition.builder().name(springName).description(desc != null ? desc : "")
                .inputSchema(inputSchema != null && !inputSchema.isEmpty() ? inputSchema : "{}").build();
            this.mcpName = mcpName;
            this.config = config;
        }

        @Override public ToolDefinition getToolDefinition() { return def; }

        @Override
        public String call(String input) {
            Map<String, Object> args;
            try { args = (input != null && !input.isBlank()) ? mapper.readValue(input, Map.class) : Map.of(); }
            catch (Exception e) { args = Map.of("input", input); }
            return callTool(config, mcpName, args);
        }
    }
}
