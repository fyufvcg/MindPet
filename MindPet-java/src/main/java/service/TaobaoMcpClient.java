package service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import util.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class TaobaoMcpClient {

    private static final byte[] HEADER_DELIM = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);

    private final List<String> command;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Logger logger;
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();
    private final Object bufferLock = new Object();
    private final ByteArrayOutputStream incoming = new ByteArrayOutputStream();

    private volatile Process process;
    private volatile OutputStream stdin;

    public TaobaoMcpClient(
        @Value("${app.food.delivery.command:mcp-taobao-server}") String command,
        Logger logger
    ) {
        this.command = parseCommand(command);
        this.logger = logger;
    }

    public synchronized String callTool(String toolName, Map<String, Object> arguments) {
        try {
            ensureStarted();
            JsonNode response = sendToolCall(toolName, arguments);
            if (response == null) return "mcp-taobao-server unavailable";

            JsonNode result = response.path("result");
            JsonNode content = result.path("content");
            if (content.isArray() && !content.isEmpty()) {
                String text = content.get(0).path("text").asText("");
                if (!text.isBlank()) return text;
            }
            return result.isMissingNode() ? response.toString() : result.toString();
        } catch (Exception e) {
            logger.log("ERROR", "mcp-taobao-server failed: " + toolName + " - " + e.getMessage());
            return "mcp-taobao-server failed: " + e.getMessage();
        }
    }

    private void ensureStarted() throws IOException {
        if (process != null && process.isAlive() && stdin != null) return;

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        process = pb.start();
        stdin = process.getOutputStream();

        startReader(process.getInputStream());
        startErrorLogger(process.getErrorStream());
        initialize();
    }

    private void initialize() throws IOException {
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", "2025-03-26");
        params.set("capabilities", mapper.createObjectNode());
        ObjectNode clientInfo = mapper.createObjectNode();
        clientInfo.put("name", "mindpet-bot");
        clientInfo.put("version", "1.0.0");
        params.set("clientInfo", clientInfo);

        sendRequest("initialize", params).join();

        ObjectNode notif = mapper.createObjectNode();
        notif.put("jsonrpc", "2.0");
        notif.put("method", "notifications/initialized");
        writeFrame(notif);
    }

    private JsonNode sendToolCall(String toolName, Map<String, Object> arguments) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("method", "tools/call");
        body.put("id", nextId.getAndIncrement());

        ObjectNode params = mapper.createObjectNode();
        params.put("name", toolName);
        ObjectNode argsNode = mapper.createObjectNode();
        for (Map.Entry<String, Object> e : arguments.entrySet()) {
            putJsonValue(argsNode, e.getKey(), e.getValue());
        }
        params.set("arguments", argsNode);
        body.set("params", params);

        return send(body).get(60, TimeUnit.SECONDS);
    }

    private CompletableFuture<JsonNode> sendRequest(String method, ObjectNode params) throws IOException {
        ObjectNode body = mapper.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("method", method);
        body.put("id", nextId.getAndIncrement());
        body.set("params", params);
        return send(body);
    }

    private CompletableFuture<JsonNode> send(ObjectNode body) throws IOException {
        int id = body.get("id").asInt();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        writeFrame(body);
        return future;
    }

    private void writeFrame(ObjectNode body) throws IOException {
        byte[] payload = mapper.writeValueAsBytes(body);
        byte[] header = ("Content-Length: " + payload.length + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
        synchronized (writeLock) {
            stdin.write(header);
            stdin.write(payload);
            stdin.flush();
        }
    }

    private void startReader(InputStream stdout) {
        Thread t = new Thread(() -> {
            byte[] chunk = new byte[8192];
            try {
                int n;
                while ((n = stdout.read(chunk)) != -1) {
                    synchronized (bufferLock) {
                        incoming.write(chunk, 0, n);
                        parseIncoming();
                    }
                }
            } catch (Exception ignored) {
            }
        }, "mcp-taobao-stdout");
        t.setDaemon(true);
        t.start();
    }

    private void startErrorLogger(InputStream stderr) {
        Thread t = new Thread(() -> {
            try (InputStream in = stderr) {
                StringBuilder line = new StringBuilder();
                int b;
                while ((b = in.read()) != -1) {
                    if (b == '\n') {
                        String msg = line.toString().trim();
                        if (!msg.isBlank()) logger.log("WARN", "[mcp-taobao] " + msg);
                        line.setLength(0);
                    } else if (b != '\r') {
                        line.append((char) b);
                    }
                }
            } catch (Exception ignored) {
            }
        }, "mcp-taobao-stderr");
        t.setDaemon(true);
        t.start();
    }

    private void parseIncoming() {
        byte[] data = incoming.toByteArray();
        int offset = 0;

        while (true) {
            int headerEnd = indexOf(data, offset, HEADER_DELIM);
            if (headerEnd < 0) break;

            String headers = new String(data, offset, headerEnd - offset, StandardCharsets.ISO_8859_1);
            int contentLength = parseContentLength(headers);
            if (contentLength <= 0) break;

            int bodyStart = headerEnd + HEADER_DELIM.length;
            if (data.length < bodyStart + contentLength) break;

            String json = new String(data, bodyStart, contentLength, StandardCharsets.UTF_8);
            handleMessage(json);
            offset = bodyStart + contentLength;
        }

        if (offset > 0) {
            incoming.reset();
            incoming.write(data, offset, data.length - offset);
        }
    }

    private void handleMessage(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            if (node.has("id")) {
                int id = node.path("id").asInt();
                CompletableFuture<JsonNode> future = pending.remove(id);
                if (future != null) future.complete(node);
            }
        } catch (Exception e) {
            logger.log("WARN", "mcp-taobao parse failed: " + e.getMessage());
        }
    }

    private int parseContentLength(String headers) {
        for (String line : headers.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) {
                try {
                    return Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                } catch (Exception ignored) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private int indexOf(byte[] data, int from, byte[] pattern) {
        outer:
        for (int i = from; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private List<String> parseCommand(String command) {
        String trimmed = command == null ? "" : command.trim();
        if (trimmed.isBlank()) return List.of("mcp-taobao-server");
        String[] parts = trimmed.split("\\s+");
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            if (!part.isBlank()) result.add(part);
        }
        return result.isEmpty() ? List.of("mcp-taobao-server") : result;
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
