package service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Service;
import util.Logger;

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

    public McpManager(Logger logger) {
        this.logger = logger;
    }

    /** 前端同步 MCP 配置 */
    public synchronized void syncServers(List<McpServerConfig> configs) {
        Set<String> newIds = new HashSet<>();
        for (McpServerConfig cfg : configs) {
            if (!cfg.enabled || cfg.url == null || cfg.url.isBlank()) continue;
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

    @Override
    public ToolCallback[] getToolCallbacks() {
        List<ToolCallback> list = new ArrayList<>();
        for (McpConnection conn : connections.values()) list.addAll(conn.toolCallbacks);
        return list.toArray(new ToolCallback[0]);
    }

    // ==================== 连接 & 工具发现 ====================

    private void connectAndRegister(McpServerConfig cfg) {
        try {
            logger.log("INFO", "[MCP] 正在连接 " + cfg.name + " (" + cfg.url + ")");
            if (!initAndCheck(cfg)) {
                logger.log("WARN", "[MCP] " + cfg.name + " 初始化失败");
                return;
            }
            List<ToolDef> tools = listTools(cfg);
            if (tools.isEmpty()) {
                logger.log("WARN", "[MCP] " + cfg.name + " 没有工具");
                return;
            }
            List<ToolCallback> cbs = new ArrayList<>();
            for (ToolDef tool : tools) {
                String name = sanitizeName(cfg.id, tool.name);
                String schema = tool.inputSchema != null ? mapper.writeValueAsString(tool.inputSchema) : "{}";
                cbs.add(new McpToolCallback(name, tool.description, schema, tool.name, cfg));
            }
            connections.put(cfg.id, new McpConnection(cfg, cbs));
            logger.log("INFO", "[MCP] " + cfg.name + " 已连接，" + tools.size() + " 个工具");
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

            JsonNode resp = sendJsonRpc(cfg, req);
            if (resp == null || !resp.has("result")) return List.of();
            JsonNode arr = resp.get("result").get("tools");
            if (arr == null || !arr.isArray()) return List.of();

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
            return List.of();
        }
    }

    private String callTool(McpServerConfig cfg, String toolName, Map<String, Object> arguments) {
        try {
            ObjectNode params = mapper.createObjectNode();
            params.put("name", toolName);
            params.set("arguments", mapper.valueToTree(arguments != null ? arguments : Map.of()));

            ObjectNode req = mapper.createObjectNode();
            req.put("jsonrpc", "2.0"); req.put("method", "tools/call"); req.put("id", 3);
            req.set("params", params);

            JsonNode resp = sendJsonRpc(cfg, req);
            if (resp == null) return "MCP 无响应";
            if (resp.has("error")) return "MCP error: " + resp.get("error");

            JsonNode content = resp.path("result").path("content");
            if (content.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode c : content) {
                    String t = c.path("text").asText("");
                    if (!t.isBlank()) sb.append(t).append("\n");
                }
                String r = sb.toString().trim();
                if (!r.isEmpty()) return r;
            }
            return resp.path("result").toString();
        } catch (Exception e) {
            return "MCP 异常: " + e.getMessage();
        }
    }

    // ==================== HTTP ====================

    private JsonNode sendJsonRpc(McpServerConfig cfg, ObjectNode request) {
        try {
            String body = mapper.writeValueAsString(request);
            String method = request.path("method").asText("?");

            HttpURLConnection conn = (HttpURLConnection) URI.create(cfg.url).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(cfg.timeout > 0 ? cfg.timeout * 1000 : 30000);
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
            logger.log("DEBUG", "[MCP] ← " + cfg.name + " body(" + raw.length() + "): " + raw.substring(0, Math.min(200, raw.length())).replace("\n", "\\n"));

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
        }
    }

    // ==================== helper ====================

    private String sanitizeName(String serverId, String toolName) {
        String p = serverId.replaceAll("[^a-zA-Z0-9_]", "_");
        if (p.length() > 20) p = p.substring(0, 20);
        return p + "__" + toolName.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    // ==================== 内部类 ====================

    public static class McpServerConfig {
        public String id, name, url, apiKey, type = "stream";
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
            return Objects.equals(config.url, o.url) && Objects.equals(config.apiKey, o.apiKey);
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
