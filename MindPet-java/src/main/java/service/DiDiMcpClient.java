package service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import util.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 滴滴出行 MCP 客户端 — 调用滴滴官方云端 MCP 服务。
 * 参照 TicketQueryTool 的 JSON-RPC 模式，不经过 Spring AI MCP 集成。
 *
 * API Key 从 application.yml 读取，需先去 https://mcp.didichuxing.com/ 登录获取。
 */
@Service
public class DiDiMcpClient {

    private final String mcpUrl;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Logger logger;

    public DiDiMcpClient(
            @Value("${didi.mcp.key:}") String apiKey,
            @Value("${didi.mcp.sandbox:true}") boolean sandbox,
            Logger logger) {
        String base = sandbox
            ? "https://mcp.didichuxing.com/mcp-servers-sandbox"
            : "https://mcp.didichuxing.com/mcp-servers";
        this.mcpUrl = base + "?key=" + (apiKey.isBlank() ? "YOUR_KEY" : apiKey);
        this.logger = logger;
        if (apiKey.isBlank()) {
            logger.log("WARN", "滴滴 MCP Key 未配置，打车功能不可用");
        } else {
            logger.log("INFO", "滴滴 MCP 客户端已初始化" + (sandbox ? " (沙箱模式)" : " (生产模式)"));
        }
    }

    public synchronized JsonNode callTool(String toolName, Map<String, Object> arguments) {
        try {
            return doCallTool(toolName, arguments);
        } catch (Exception e) {
            logger.log("ERROR", "滴滴 MCP 工具调用失败: " + toolName + " — " + e.getMessage());
            return null;
        }
    }

    // ==================== MCP 协议 ====================

    private JsonNode doCallTool(String toolName, Map<String, Object> arguments) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("method", "tools/call");
        body.put("id", 1);
        ObjectNode callParams = mapper.createObjectNode();
        callParams.put("name", toolName);
        ObjectNode argsNode = mapper.createObjectNode();
        for (Map.Entry<String, Object> e : arguments.entrySet()) {
            argsNode.put(e.getKey(), String.valueOf(e.getValue()));
        }
        callParams.set("arguments", argsNode);
        body.set("params", callParams);

        logger.log("INFO", "  -> 滴滴 MCP: " + toolName);
        return send(body);
    }

    private JsonNode send(ObjectNode body) throws Exception {
        String jsonBody = mapper.writeValueAsString(body);
        URL url = new URI(mcpUrl).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        if (status < 200 || status >= 300) {
            logger.log("WARN", "滴滴 MCP 返回 " + status);
            return null;
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) { sb.append(line); }
            String raw = sb.toString().trim();
            return raw.isEmpty() ? null : mapper.readTree(raw);
        }
    }
}
