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

@Service
public class HowToCookMcpClient {

    private final String mcpUrl;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Logger logger;
    private String sessionId;
    private String lastSessionHeader;

    public HowToCookMcpClient(
        @Value("${app.food.howtocook.mcp-url:http://127.0.0.1:3000/mcp}") String mcpUrl,
        Logger logger
    ) {
        this.mcpUrl = mcpUrl;
        this.logger = logger;
    }

    public synchronized String callTool(String toolName, Map<String, Object> arguments) {
        try {
            JsonNode response = callMcp(toolName, arguments);
            if (response == null) return "HowToCook MCP unavailable";

            JsonNode result = response.path("result");
            JsonNode content = result.path("content");
            if (content.isArray() && !content.isEmpty()) {
                String text = content.get(0).path("text").asText("");
                if (!text.isBlank()) return text;
            }
            return result.isMissingNode() ? response.toString() : result.toString();
        } catch (Exception e) {
            logger.log("ERROR", "HowToCook MCP failed: " + e.getMessage());
            return "HowToCook MCP failed: " + e.getMessage();
        }
    }

    private JsonNode callMcp(String toolName, Map<String, Object> arguments) throws Exception {
        if (sessionId == null) initSession();

        ObjectNode body = mapper.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("method", "tools/call");
        body.put("id", 1);

        ObjectNode params = mapper.createObjectNode();
        params.put("name", toolName);
        ObjectNode argsNode = mapper.createObjectNode();
        for (Map.Entry<String, Object> e : arguments.entrySet()) {
            putJsonValue(argsNode, e.getKey(), e.getValue());
        }
        params.set("arguments", argsNode);
        body.set("params", params);
        return send(body);
    }

    private void initSession() throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", "2025-03-26");
        params.set("capabilities", mapper.createObjectNode());
        ObjectNode clientInfo = mapper.createObjectNode();
        clientInfo.put("name", "mindpet-bot");
        clientInfo.put("version", "1.0.0");
        params.set("clientInfo", clientInfo);

        ObjectNode body = mapper.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("method", "initialize");
        body.put("id", 0);
        body.set("params", params);

        JsonNode response = send(body);
        if (response == null) return;

        sessionId = lastSessionHeader;

        ObjectNode notif = mapper.createObjectNode();
        notif.put("jsonrpc", "2.0");
        notif.put("method", "notifications/initialized");
        sendNoResponse(notif);
    }

    private JsonNode send(ObjectNode body) throws Exception {
        String jsonBody = mapper.writeValueAsString(body);
        URL url = new URI(mcpUrl).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json, text/event-stream");
        if (sessionId != null && !sessionId.isBlank()) {
            conn.setRequestProperty("Mcp-Session-Id", sessionId);
        }
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        lastSessionHeader = conn.getHeaderField("Mcp-Session-Id");
        if (lastSessionHeader == null || lastSessionHeader.isBlank()) {
            lastSessionHeader = conn.getHeaderField("MCP-Session-Id");
        }
        if (status < 200 || status >= 300) return null;

        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder jsonData = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data:")) {
                    // SSE format: extract JSON from data: lines
                    String data = line.substring(5).trim();
                    if (!data.isEmpty()) jsonData.append(data);
                }
            }
            String raw = jsonData.toString().trim();
            if (!raw.isEmpty()) return mapper.readTree(raw);
            return null;
        }
    }

    private void sendNoResponse(ObjectNode body) {
        try {
            send(body);
        } catch (Exception ignored) {
        }
    }

    private String headerValue(String name) {
        return lastSessionHeader;
    }

    private void putJsonValue(ObjectNode node, String key, Object value) {
        if (value == null) {
            node.putNull(key);
        } else if (value instanceof Integer i) {
            node.put(key, i);
        } else if (value instanceof Long l) {
            node.put(key, l);
        } else if (value instanceof Double d) {
            node.put(key, d);
        } else if (value instanceof Float f) {
            node.put(key, f);
        } else if (value instanceof Boolean b) {
            node.put(key, b);
        } else {
            node.put(key, String.valueOf(value));
        }
    }
}
