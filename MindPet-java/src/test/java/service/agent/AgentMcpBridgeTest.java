package service.agent;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import service.McpManager;
import tool.ToolUserContext;
import util.Logger;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AgentMcpBridgeTest {
    HttpServer server;
    final ObjectMapper json = new ObjectMapper();
    final AtomicReference<JsonNode> received = new AtomicReference<>();
    volatile boolean fail;

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            JsonNode request = json.readTree(exchange.getRequestBody());
            Object result = switch (request.path("method").asText()) {
                case "tools/list", "mindpet/mcp-list" -> Map.of("tools", List.of(Map.of("name", "desktop__file__read_file",
                    "description", "read", "inputSchema", Map.of("type", "object", "properties", Map.of()))));
                case "tools/call", "mindpet/mcp-call" -> {
                    received.set(request);
                    yield Map.of("isError", fail, "content", List.of(Map.of("type", "text", "text", fail ? "文件不存在" : "未找到搜索结果")),
                        "structuredContent", Map.of("success", !fail, "state", Map.of("path", "sample.txt")));
                }
                default -> Map.of();
            };
            byte[] body = json.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", request.path("id").asText("0"), "result", result));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        ToolUserContext.set("user", "session"); ToolUserContext.setRequestId("message");
        ToolUserContext.setExecution(Map.of("taskId", "task", "workspacePath", "D:/work", "deadline", Long.toString(System.currentTimeMillis()+30000)));
    }
    @AfterEach void cleanup() { server.stop(0); ToolUserContext.clear(); }

    @Test void bridgeCarriesExecutionContextOutsideToolArguments() throws Exception {
        McpManager manager = manager();
        JsonNode result = json.readTree(manager.getToolCallbacks()[0].call("{\"file_path\":\"sample.txt\"}"));
        JsonNode params = received.get().path("params");
        assertThat(params.path("_meta").path("mindpet/context").path("sessionId").asText()).isEqualTo("session");
        assertThat(params.path("_meta").path("mindpet/context").path("messageId").asText()).isEqualTo("message");
        assertThat(params.path("arguments").size()).isEqualTo(1);
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.path("content").asText()).contains("未找到搜索结果");
        assertThat(AgentTaskService.isFailure(result.toString())).isFalse();
    }

    @Test void realInvocationErrorsRemainDistinctFromEmptyResults() throws Exception {
        fail = true;
        JsonNode result = json.readTree(manager().getToolCallbacks()[0].call("{}"));
        assertThat(result.path("success").asBoolean()).isFalse();
        assertThat(AgentTaskService.isFailure(result.toString())).isTrue();
        assertThat(result.path("content").asText()).contains("文件不存在");
    }

    @Test void userConfiguredVerticalServiceIsNotBlockedAsRetiredBuiltin() {
        McpManager manager = manager();
        McpManager.McpServerConfig config = new McpManager.McpServerConfig();
        config.id = "mcdonalds"; config.name = "麦当劳"; config.source = "USER_MCP";
        config.url = "http://127.0.0.1:" + server.getAddress().getPort();
        var builtin = new McpManager.McpServerConfig(); builtin.id = "test"; builtin.source = "DESKTOP_BUILTIN"; builtin.name = "test"; builtin.url = config.url;
        config.url = "http://invalid.example/never-called";
        manager.syncServers(List.of(config, builtin));
        assertThat(manager.getToolCallbacks()).hasSize(2);
        assertThat(manager.callbacksFor(Set.of())).hasSize(1);
        assertThat(manager.callbacksFor(Set.of("mcdonalds"))).hasSize(2);
        ToolUserContext.setExecution(Map.of("selectedMcpServerIds", "[\"mcdonalds\"]"));
        var external = Arrays.stream(manager.getToolCallbacks()).filter(callback -> !manager.isBuiltin(callback)).findFirst().orElseThrow();
        external.call("{}");
        assertThat(received.get().path("method").asText()).isEqualTo("mindpet/mcp-call");
        assertThat(received.get().path("params").path("serverId").asText()).isEqualTo("mcdonalds");
    }

    private McpManager manager() {
        McpManager manager = new McpManager(mock(Logger.class));
        McpManager.McpServerConfig config = new McpManager.McpServerConfig();
        config.source = "DESKTOP_BUILTIN";
        config.id = "test"; config.name = "test"; config.url = "http://127.0.0.1:" + server.getAddress().getPort();
        manager.syncServers(List.of(config));
        return manager;
    }
}
