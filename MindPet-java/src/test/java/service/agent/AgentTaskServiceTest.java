package service.agent;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;
import reactor.core.publisher.Flux;
import service.McpManager;
import tool.ToolUserContext;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Real Spring AI ToolCallAdvisor + ToolCallingManager, scripted model, real SQLite; no external API calls. */
class AgentTaskServiceTest {
    @TempDir Path temp;
    AgentTaskStore store;
    AgentTaskService tasks;

    @BeforeEach void setup() {
        SQLiteDataSource database = new SQLiteDataSource();
        database.setUrl("jdbc:sqlite:" + temp.resolve("agent.db"));
        store = new AgentTaskStore(new JdbcTemplate(database));
        McpManager manager = mock(McpManager.class);
        org.mockito.Mockito.when(manager.serverIdOf(org.mockito.ArgumentMatchers.any())).thenReturn("");
        tasks = new AgentTaskService(store, manager);
        ToolUserContext.set("user", "session");
        ToolUserContext.setRequestId("request");
        ToolUserContext.setExecution(Map.of("workspacePath", temp.toString()));
    }
    @AfterEach void cleanup() { tasks.close(); ToolUserContext.clear(); }

    @Test void frameworkExecutesMoreThanTwoCallsAndPreservesToolPairing() {
        AtomicInteger modelCalls = new AtomicInteger(), executions = new AtomicInteger();
        ToolCallback read = tool("read", args -> {
            assertThat(ToolUserContext.getSessionId()).isEqualTo("session");
            assertThat(ToolUserContext.getExecution()).containsEntry("workspacePath", temp.toString());
            return "file " + executions.incrementAndGet();
        });
        ChatModel model = scripted(prompt -> {
            int turn = modelCalls.incrementAndGet();
            assertThat(((ToolCallingChatOptions)prompt.getOptions()).getInternalToolExecutionEnabled()).isFalse();
            assertPaired(prompt.getInstructions());
            return turn <= 5 ? call("read", "{\"file\":" + turn + "}", "c" + turn) : answer("已读取五个文件");
        });
        ChatResponse result = tasks.call(attach(model, new ToolCallback[]{read}, new ToolCallback[]{read}));
        assertThat(result.getResult().getOutput().getText()).isEqualTo("已读取五个文件");
        assertThat(executions).hasValue(5);
        assertThat(result.getMetadata().getUsage().getPromptTokens()).isEqualTo(60);
        assertThat(result.getMetadata().getUsage().getCompletionTokens()).isEqualTo(12);
        assertThat(ToolUserContext.isToolsUsed()).isTrue();
        assertThat(ToolUserContext.get()).isEqualTo("user");
        assertThat(ToolUserContext.getSessionId()).isEqualTo("session");
        assertThat(store.list("user", "session").get(0)).containsEntry("status", "completed");
    }

    @Test void streamingFrameworkCanDiscoverToolsDuringTheSameTask() {
        AtomicInteger turns = new AtomicInteger(), executed = new AtomicInteger();
        ToolCallback hidden = tool("desktop_tools__desktop__file__read_file", args -> {
            executed.incrementAndGet(); return "文件内容";
        });
        tasks.close();
        McpManager manager = mock(McpManager.class);
        org.mockito.Mockito.when(manager.serverIdOf(org.mockito.ArgumentMatchers.any())).thenReturn("external-files");
        org.mockito.Mockito.when(manager.isManaged(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        tasks = new AgentTaskService(store, manager);
        ToolUserContext.setExecution(Map.of("selectedMcpServerIds", "[\"external-files\"]"));
        ChatModel model = scripted(prompt -> {
            int turn = turns.incrementAndGet();
            var names = ((ToolCallingChatOptions)prompt.getOptions()).getToolCallbacks().stream()
                .map(c -> c.getToolDefinition().name()).toList();
            if (turn == 1) {
                assertThat(names).doesNotContain(hidden.getToolDefinition().name());
                return call("agent_discover_tools", "{\"group\":\"external-files\"}", "discover");
            }
            assertThat(names).contains(hidden.getToolDefinition().name());
            assertPaired(prompt.getInstructions());
            return turn == 2 ? call(hidden.getToolDefinition().name(), "{}", "read") : answer("完成");
        });
        StringBuilder text = new StringBuilder();
        ChatResponse finalResponse = tasks.stream(attach(model, new ToolCallback[0], new ToolCallback[]{hidden}))
            .doOnNext(r -> { if (r.getResult() != null) text.append(r.getResult().getOutput().getText()); }).blockLast();
        tasks.releaseStreamContext();
        assertThat(text.toString()).contains("完成");
        assertThat(executed).hasValue(1);
        assertThat(finalResponse.getMetadata().getUsage().getPromptTokens()).isEqualTo(30);
        assertThat(ToolUserContext.isToolsUsed()).isTrue();
    }

    @Test void streamingUsageDoesNotCountAggregatedResponsesTwice() {
        AtomicInteger turns = new AtomicInteger();
        ToolCallback read = tool("read", args -> "ok");
        ChatModel model = scripted(prompt -> turns.incrementAndGet() <= 5
            ? call("read", "{}", "c" + turns.get()) : answer("done"));
        ChatResponse response = tasks.stream(attach(model, new ToolCallback[]{read}, new ToolCallback[]{read})).blockLast();
        tasks.releaseStreamContext();
        assertThat(turns).hasValue(6);
        assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(60);
        assertThat(response.getMetadata().getUsage().getCompletionTokens()).isEqualTo(12);
        var snapshot = store.snapshot(store.get("user", store.list("user", "session").get(0).get("id").toString()));
        assertThat((List<?>)snapshot.get("usageRounds")).hasSize(5);
        assertThat(snapshot).containsEntry("promptTokens", 60);
    }

    @Test void toolOutputsRemainIntactWithoutBudgetPressure() {
        AtomicInteger turns = new AtomicInteger();
        String body = "very-long-content ".repeat(700);
        ToolCallback read = tool("read", args -> body);
        ChatModel model = scripted(prompt -> {
            int turn = turns.incrementAndGet();
            assertPaired(prompt.getInstructions());
            if (turn == 5) {
                var results = prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast).toList();
                assertThat(results.get(0).getResponses().get(0).responseData()).isEqualTo(body);
                assertThat(results.get(results.size() - 1).getResponses().get(0).responseData()).isEqualTo(body);
            }
            return turn <= 4 ? call("read", "{}", "c" + turn) : answer("done");
        });
        tasks.call(attach(model, new ToolCallback[]{read}, new ToolCallback[]{read}));
        assertThat(turns).hasValue(5);
    }

    @Test void onlyIdenticalFailedArgumentsAreLimited() {
        AtomicInteger turns = new AtomicInteger(), executed = new AtomicInteger();
        ToolCallback read = tool("read", args -> { executed.incrementAndGet(); return "{\"success\":false,\"code\":\"NOT_FOUND\"}"; });
        ChatModel model = scripted(prompt -> {
            int turn = turns.incrementAndGet();
            if (turn == 4) {
                assertThat(((ToolResponseMessage)prompt.getInstructions().get(prompt.getInstructions().size()-1))
                    .getResponses().get(0).responseData()).contains("REPEATED_FAILURE");
            }
            return turn <= 4 ? call("read", turn == 4 ? "{\"path\":\"other\"}" : "{}", "c"+turn) : answer("路径无法读取");
        });
        tasks.call(attach(model, new ToolCallback[]{read}, new ToolCallback[]{read}));
        assertThat(executed).hasValue(3);
    }

    @Test void restartRecoversCheckpointWithoutReplayingUnknownWrites() {
        String id = "interrupted-task";
        List<Message> history = List.of(new UserMessage("修改合同"), call("write", "{}", "pending"));
        store.save(id, "user", "session", "old-request", "running", "修改合同", Map.of(
            "messages", AgentMessages.encode(history), "enabledTools", List.of("write"), "plan", "检查修改后的合同"));
        // Reopening a store turns stale running tasks into interrupted tasks.
        SQLiteDataSource database = new SQLiteDataSource(); database.setUrl("jdbc:sqlite:" + temp.resolve("agent.db"));
        new AgentTaskStore(new JdbcTemplate(database));
        assertThat(store.get("user", id)).containsEntry("status", "interrupted");
        ToolUserContext.setExecution(Map.of("resumeTaskId", id, "workspacePath", temp.toString()));
        AtomicInteger writes = new AtomicInteger();
        ToolCallback write = tool("write", args -> { writes.incrementAndGet(); return "written"; });
        ChatModel model = scripted(prompt -> {
            assertPaired(prompt.getInstructions());
            assertThat(prompt.getInstructions().stream().filter(m -> m instanceof ToolResponseMessage)
                .map(m -> ((ToolResponseMessage)m).getResponses().get(0).responseData()).toList())
                .anyMatch(s -> s.contains("INTERRUPTED_UNKNOWN_OUTCOME"));
            return answer("需要先确认合同状态");
        });
        tasks.call(attach(model, new ToolCallback[]{write}, new ToolCallback[]{write}));
        assertThat(writes).hasValue(0);
        assertThat(store.get("user", id)).containsEntry("goal", "修改合同").containsEntry("status", "needs_input");
    }

    @Test void oversizedOutputsAreDurableAndCanBePaged() {
        AtomicInteger turns = new AtomicInteger();
        ToolCallback read = tool("read", args -> "x".repeat(40000) + "THE_END");
        ChatModel model = scripted(prompt -> {
            int turn = turns.incrementAndGet();
            if (turn == 2) {
                var response = ((ToolResponseMessage)prompt.getInstructions().get(prompt.getInstructions().size()-1)).getResponses().get(0);
                assertThat(response.responseData()).hasSizeLessThan(25000).contains("\"call_id\":\"read-call\"");
                return call("agent_read_output", "{\"call_id\":\"read-call\",\"offset\":39990,\"length\":100}", "page");
            }
            if (turn == 3) {
                var response = ((ToolResponseMessage)prompt.getInstructions().get(prompt.getInstructions().size()-1)).getResponses().get(0);
                assertThat(response.responseData()).contains("THE_END"); return answer("完成");
            }
            return call("read", "{}", "read-call");
        });
        tasks.call(attach(model, new ToolCallback[]{read}, new ToolCallback[]{read}));
    }

    @Test void cancellationInterruptsTheFrameworkAndPersistsState() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        ToolCallback wait = tool("wait", args -> {
            started.countDown();
            try { Thread.sleep(30000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "stopped";
        });
        ChatModel model = scripted(prompt -> call("wait", "{}", "wait-call"));
        ExecutorService requestThread = Executors.newSingleThreadExecutor();
        try {
            Future<?> future = requestThread.submit(() -> {
                ToolUserContext.set("user", "session"); ToolUserContext.setRequestId("cancel-request");
                try { tasks.call(attach(model, new ToolCallback[]{wait}, new ToolCallback[]{wait})); }
                finally { ToolUserContext.clear(); }
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(tasks.cancel("user", "session", "cancel-request")).isTrue();
            assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
            assertThat(store.list("user", "session").get(0)).containsEntry("status", "cancelled");
        } finally { requestThread.shutdownNow(); }
    }

    @Test void ordinaryKnowledgeAnswerDoesNotExecuteSearch() {
        AtomicInteger searches = new AtomicInteger();
        ToolCallback search = tool("web_search", args -> { searches.incrementAndGet(); return "results"; });
        ChatResponse response = tasks.call(attach(scripted(prompt -> answer("Java 的接口定义行为契约。")),
            new ToolCallback[0], new ToolCallback[]{search}));
        assertThat(response.getResult().getOutput().getText()).contains("接口");
        assertThat(searches).hasValue(0);
        assertThat(AgentPolicy.SEARCH).contains("常识、概念解释、推理和一般建议", "除非确实发生了调用错误");
    }

    @Test void checkpointRedactsCredentialsAndScopesOutputToItsTask() {
        store.save("one", "user", "session", "r", "failed", "password=private-secret", Map.of("text", "api_key=sk-sensitive-value-12345"));
        store.output("one", "c", "password=private-secret");
        assertThat(store.get("user", "one").toString()).doesNotContain("private-secret", "sk-sensitive-value-12345");
        assertThat(store.readOutput("other", "c", 0, 100)).contains("没有这个输出");
        assertThatThrownBy(() -> store.get("different-user", "one")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void toolImagesActuallyReachTheNextModelTurnButAreNotPersisted() {
        AtomicInteger turns = new AtomicInteger();
        String png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/f9sAAAAASUVORK5CYII=";
        ToolCallback screenshot = tool("screenshot", args -> "{\"mindpetToolResult\":true,\"success\":true,\"content\":\"截图完成\",\"images\":[{\"mimeType\":\"image/png\",\"data\":\"" + png + "\"}]}");
        ChatModel model = scripted(prompt -> {
            if (turns.incrementAndGet() == 1) return call("screenshot", "{}", "screen");
            assertThat(prompt.getInstructions().stream().filter(m -> m instanceof UserMessage)
                .map(m -> ((UserMessage)m).getMedia().size()).mapToInt(Integer::intValue).sum()).isEqualTo(1);
            assertPaired(prompt.getInstructions()); return answer("已看到截图");
        });
        tasks.stream(attach(model, new ToolCallback[]{screenshot}, new ToolCallback[]{screenshot})).blockLast();
        tasks.releaseStreamContext();
        var row = store.get("user", store.list("user", "session").get(0).get("id").toString());
        assertThat(row.get("snapshot").toString()).doesNotContain(png);
    }

    @Test void frameworkStopsAtTaskBudgetAndSavesProgress() {
        org.springframework.test.util.ReflectionTestUtils.setField(tasks, "maxTurns", 3);
        AtomicInteger count = new AtomicInteger();
        ToolCallback read = tool("read", args -> "ok");
        ChatModel model = scripted(prompt -> call("read", "{\"n\":" + count.incrementAndGet() + "}", "c"+count.get()));
        assertThatThrownBy(() -> tasks.call(attach(model, new ToolCallback[]{read}, new ToolCallback[]{read})))
            .isInstanceOf(IllegalStateException.class);
        assertThat(count).hasValue(3);
        assertThat(store.list("user", "session").get(0)).containsEntry("status", "budget_exhausted");
    }

    @Test void compactionRetainsCompleteToolCyclesAndOutputReferences() {
        var clients = mock(service.DynamicChatClientFactory.class);
        org.mockito.Mockito.when(clients.effectiveModel()).thenReturn("fixture");
        org.mockito.Mockito.when(clients.buildBackground(org.mockito.ArgumentMatchers.anyLong())).thenReturn(
            ChatClient.builder(scripted(prompt -> answer("{\"goal\":\"处理文件\",\"constraints\":[],\"decisions\":[],\"completed\":[],\"evidence\":[\"call_id=c1\"],\"pending\":[],\"failures\":[],\"artifacts\":[]}"))).build());
        SQLiteDataSource database = new SQLiteDataSource();
        database.setUrl("jdbc:sqlite:" + temp.resolve("agent.db"));
        var archive = new AgentContextStore(new JdbcTemplate(database));
        var contexts = new AgentContextManager(archive, clients);
        org.springframework.test.util.ReflectionTestUtils.setField(contexts, "maxInput", 8000);
        tasks.setContextServices(contexts, archive);
        AtomicInteger turns = new AtomicInteger();
        ToolCallback read = tool("read", args -> "data ".repeat(2000));
        ChatModel model = scripted(prompt -> {
            assertPaired(prompt.getInstructions());
            int turn = turns.incrementAndGet();
            if (turn >= 5) {
                assertThat(prompt.getInstructions().stream().map(m -> Objects.toString(m.getText(), "")
                        + (m instanceof ToolResponseMessage toolResult ? toolResult.getResponses().toString() : "")).toList())
                    .anyMatch(s -> s.contains("call_id=c1"));
            }
            return turn <= 8 ? call("read", "{\"file\":"+turn+"}", "c"+turn) : answer("已处理八个文件");
        });
        tasks.call(attach(model, new ToolCallback[]{read}, new ToolCallback[]{read}));
        assertThat(turns).hasValue(9);
    }

    @Test void completionReportPreservesBlockedStateEvenWithoutAPlan() {
        AtomicInteger turns = new AtomicInteger();
        ChatModel model = scripted(prompt -> turns.incrementAndGet() == 1
            ? call("agent_report_completion", "{\"status\":\"needs_input\",\"evidence\":\"缺少目标目录\"}", "report")
            : answer("请提供目标目录"));
        tasks.call(attach(model, new ToolCallback[0], new ToolCallback[0]));
        assertThat(store.list("user", "session").get(0)).containsEntry("status", "needs_input");
    }

    @Test void cancellationDuringRoutingPreventsAnyLaterToolExecution() {
        tasks.cancel("user", "session", "request");
        AtomicInteger count = new AtomicInteger();
        ToolCallback write = tool("write", args -> { count.incrementAndGet(); return "written"; });
        assertThatThrownBy(() -> attach(scripted(prompt -> call("write", "{}", "write")),
            new ToolCallback[]{write}, new ToolCallback[]{write})).isInstanceOf(IllegalStateException.class).hasMessageContaining("取消");
        assertThat(count).hasValue(0);
        assertThat(store.list("user", "session").get(0)).containsEntry("status", "cancelled");
    }

    @Test void recoveryPreservesKnownResultsWithinAnInterruptedBatch() {
        List<Message> history = new ArrayList<>();
        history.add(new UserMessage("处理两个文件"));
        history.add(AssistantMessage.builder().content("").toolCalls(List.of(
            new AssistantMessage.ToolCall("first", "function", "write", "{\"file\":1}"),
            new AssistantMessage.ToolCall("second", "function", "write", "{\"file\":2}"))).build());
        List<Message> restored = AgentMessages.decode(AgentMessages.encode(history));
        AgentMessages.closePendingCalls(restored, Map.of("first", "第一个文件已写入并校验"));
        assertPaired(restored);
        var results = ((ToolResponseMessage)restored.get(restored.size()-1)).getResponses();
        assertThat(results.get(0).responseData()).contains("已写入并校验");
        assertThat(results.get(1).responseData()).contains("INTERRUPTED_UNKNOWN_OUTCOME");
    }

    private ChatClient.ChatClientRequestSpec attach(ChatModel model, ToolCallback[] initial, ToolCallback[] all) {
        var spec = ChatClient.builder(model).build().prompt().system(AgentPolicy.SEARCH + AgentPolicy.EXECUTION)
            .user("完成测试任务").options(OpenAiChatOptions.builder().model("scripted").build());
        return tasks.attach(spec, "完成测试任务", initial, all);
    }

    @Test void taskBudgetReservesTheNextOutputBeforeCallingTheProvider() {
        org.springframework.test.util.ReflectionTestUtils.setField(tasks, "taskMaxTokens", 4000);
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> tasks.call(attach(scripted(p -> { calls.incrementAndGet(); return answer("done"); }), new ToolCallback[0], new ToolCallback[0])))
            .isInstanceOf(IllegalStateException.class);
        assertThat(calls).hasValue(0);
        assertThat(store.list("user", "session").get(0)).containsEntry("status", "budget_exhausted");
    }

    @Test void clearingIsBlockedDuringPreparationAndExecutionButAllowedAfterExit() {
        AtomicInteger changes = new AtomicInteger();
        tasks.beginSessionRequest("user", "session");
        assertThatThrownBy(() -> tasks.changeSession("user", "session", changes::incrementAndGet))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> tasks.beginSessionRequest("user", "session"))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        tasks.endSessionRequest("user", "session");
        var spec = attach(scripted(p -> answer("done")), new ToolCallback[0], new ToolCallback[0]);
        assertThatThrownBy(() -> tasks.changeAllSessions("user", changes::incrementAndGet))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        tasks.call(spec);
        tasks.changeSession("user", "session", changes::incrementAndGet);
        assertThat(changes).hasValue(1);
    }

    @Test void recoveryFillsEveryInterruptedExchangeBeforeTheFollowingUserMessage() {
        List<Message> history = new ArrayList<>(List.of(new UserMessage("处理文件"), call("write", "{}", "one"),
            new UserMessage("继续"), call("read", "{}", "two")));
        AgentMessages.closePendingCalls(history, Map.of("one", "已写入"));
        assertPaired(history);
        assertThat(history.get(2)).isInstanceOf(ToolResponseMessage.class);
        assertThat(history.get(3)).isInstanceOf(UserMessage.class);
        assertThat(((ToolResponseMessage)history.get(2)).getResponses().get(0).responseData()).isEqualTo("已写入");
    }

    @Test void repeatedSearchReusesTaskEvidenceWithoutCallingTheNetworkAgain() {
        AtomicInteger turns = new AtomicInteger(), searches = new AtomicInteger();
        ToolCallback search = tool("web_search", args -> { searches.incrementAndGet(); return "来源 URL: https://example.org 日期: today 结果: enough"; });
        ChatModel model = scripted(prompt -> {
            int turn = turns.incrementAndGet();
            if (turn == 3) {
                var result = (ToolResponseMessage)prompt.getInstructions().get(prompt.getInstructions().size()-1);
                assertThat(result.getResponses().get(0).responseData()).contains("\"reused\":true", "https://example.org");
                return answer("已找到结果");
            }
            return call("web_search", turn == 1 ? "{\"query\":\"Weather   Today\"}" : "{\"query\":\"weather today\"}", "s" + turn);
        });
        tasks.call(attach(model, new ToolCallback[]{search}, new ToolCallback[]{search}));
        assertThat(searches).hasValue(1);
    }

    @Test void partialProviderUsageKeepsTheProvidedSideAndLabelsTheMissingSideEstimated() {
        ChatModel model = new ChatModel() {
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(answer("已完成"))), org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
                    .usage(new org.springframework.ai.chat.metadata.DefaultUsage(0, 99)).build());
            }
        };
        var result = tasks.call(attach(model, new ToolCallback[0], new ToolCallback[0]));
        assertThat(result.getMetadata().getUsage().getPromptTokens()).isPositive();
        assertThat(result.getMetadata().getUsage().getCompletionTokens()).isEqualTo(99);
        assertThat((Boolean)result.getMetadata().get("mindpet.usageEstimated")).isTrue();
    }

    @Test void oversizedExternalSchemasCannotDisplaceResidentDesktopTools() {
        tasks.close();
        var manager = mock(McpManager.class);
        org.mockito.Mockito.when(manager.serverIdOf(org.mockito.ArgumentMatchers.any())).thenReturn("server");
        org.mockito.Mockito.when(manager.isManaged(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation ->
            ((ToolCallback)invocation.getArgument(0)).getToolDefinition().name().startsWith("external"));
        tasks = new AgentTaskService(store, manager);
        var database = new SQLiteDataSource(); database.setUrl("jdbc:sqlite:" + temp.resolve("agent.db"));
        var archive = new AgentContextStore(new JdbcTemplate(database));
        var clients = mock(service.DynamicChatClientFactory.class);
        org.mockito.Mockito.when(clients.effectiveModel()).thenReturn("fixture");
        var contexts = new AgentContextManager(archive, clients);
        org.springframework.test.util.ReflectionTestUtils.setField(contexts, "maxInput", 8000);
        tasks.setContextServices(contexts, archive);
        ToolUserContext.setExecution(Map.of("selectedMcpServerIds", "[\"server\"]"));
        ToolCallback desktop = tool("desktop__file__read_file", args -> "ok");
        ToolCallback external = new ToolCallback() {
            public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name("external__read")
                .description("巨大定义 ".repeat(6000)).inputSchema("{\"type\":\"object\"}").build(); }
            public String call(String input) { throw new AssertionError("oversized tool should not execute"); }
        };
        ChatModel model = scripted(prompt -> {
            var callbacks = ((ToolCallingChatOptions)prompt.getOptions()).getToolCallbacks();
            assertThat(callbacks.stream().map(t -> t.getToolDefinition().name())).contains("desktop__file__read_file").doesNotContain("external__read");
            assertThat(ContextTokens.messages(prompt.getInstructions()) + ContextTokens.tools(callbacks)).isLessThanOrEqualTo(8000);
            return answer("可继续使用基础工具");
        });
        tasks.call(attach(model, new ToolCallback[]{desktop, external}, new ToolCallback[]{desktop, external}));
    }

    @Test void phaseDependenciesReleaseSkillBodiesWhileDesktopToolsStayResident() {
        tasks.close();
        var skillStore = mock(config.SkillStore.class);
        org.mockito.Mockito.when(skillStore.get("user/a")).thenReturn(new config.SkillStore.SkillDef("规则 A ".repeat(3000), "a", "user", "", Map.of(), "a1"));
        org.mockito.Mockito.when(skillStore.get("user/b")).thenReturn(new config.SkillStore.SkillDef("规则 B ".repeat(3000), "b", "user", "", Map.of(), "b1"));
        var manager = mock(McpManager.class);
        org.mockito.Mockito.when(manager.serverIdOf(org.mockito.ArgumentMatchers.any())).thenReturn("");
        tasks = new AgentTaskService(store, manager, skillStore);
        ToolCallback desktop = tool("desktop__file__read_file", args -> "ok");
        AtomicInteger turns = new AtomicInteger();
        ChatModel model = scripted(prompt -> {
            int turn = turns.incrementAndGet();
            if (turn == 1) return call("agent_load_skill", "{\"id\":\"user/a\",\"pin\":true}", "a");
            if (turn == 2) return call("agent_load_skill", "{\"id\":\"user/b\"}", "b");
            String body = prompt.getInstructions().stream().filter(m -> m instanceof SystemMessage && m.getText().startsWith("[MindPet Skill references]"))
                .map(Message::getText).findFirst().orElse("");
            assertThat(ContextTokens.count(body)).isLessThanOrEqualTo(6000);
            if (turn == 3) {
                assertThat(body).contains("user/a", "user/b", "规则 A", "规则 B");
                return call("agent_update_plan", "{\"plan\":\"下一阶段只需要 A\",\"active_skill_ids\":[\"user/a\"],\"active_tool_names\":[]}", "phase");
            }
            assertThat(body).contains("user/a", "规则 A").doesNotContain("user/b", "规则 B");
            assertThat(((ToolCallingChatOptions)prompt.getOptions()).getToolCallbacks().stream().map(t -> t.getToolDefinition().name()))
                .contains("desktop__file__read_file");
            return answer("阶段已切换");
        });
        tasks.call(attach(model, new ToolCallback[]{desktop}, new ToolCallback[]{desktop}));
    }
    @Test void unknownWriteCannotBeReplayedOrReportedComplete() {
        AtomicInteger turns = new AtomicInteger(), executions = new AtomicInteger();
        ToolCallback write = tool("submit", args -> { executions.incrementAndGet(); return "{\"success\":false,\"outcome\":\"unknown\"}"; });
        ChatModel model = scripted(prompt -> switch (turns.incrementAndGet()) {
            case 1 -> call("submit", "{\"a\":1,\"b\":2}", "first");
            case 2 -> call("submit", "{\"b\":2,\"a\":1}", "replay");
            case 3 -> call("agent_report_completion", "{\"status\":\"completed\",\"evidence\":\"assumed success\"}", "report");
            default -> answer("需要核实外部状态");
        });
        tasks.call(attach(model, new ToolCallback[]{write}, new ToolCallback[]{write}));
        assertThat(executions).hasValue(1);
        var row = store.list("user", "session").get(0);
        assertThat(row).containsEntry("status", "needs_input");
        assertThat(store.snapshot(store.get("user", row.get("id").toString())).get("outcomes").toString())
            .contains("OUTCOME_UNKNOWN", "UNVERIFIED_OUTCOME");
    }

    @Test void resumeCarriesNewInstructionIntoWorkerAndDoesNotRestoreRevokedMcp() {
        store.save("old", "user", "session", "old-request", "interrupted", "原始任务", Map.of(
            "messages", AgentMessages.encode(List.of(new UserMessage("原始任务"))),
            "selectedMcpServerIds", List.of("revoked")));
        ToolUserContext.setExecution(Map.of("resumeTaskId", "old", "resumeInstruction", "输出改为 Excel", "selectedMcpServerIds", "[]"));
        assertThat(tasks.selectedMcpServers()).isEmpty();
        ChatModel model = scripted(prompt -> {
            assertThat(prompt.getInstructions().stream().map(Message::getText).toList())
                .anyMatch(text -> text.contains("输出改为 Excel"));
            return answer("按补充要求继续");
        });
        tasks.call(attach(model, new ToolCallback[0], new ToolCallback[0]));
    }

    @Test void onlyAFreshExternalObservationCanResolveAnUnknownAction() {
        AtomicInteger turns = new AtomicInteger();
        ToolCallback write = tool("submit", args -> "{\"success\":false,\"outcome\":\"unknown\"}");
        ToolCallback read = tool("inspect", args -> "actual state checked");
        ChatModel model = scripted(prompt -> switch (turns.incrementAndGet()) {
            case 1 -> call("inspect", "{}", "old-read");
            case 2 -> call("submit", "{}", "write");
            case 3 -> call("agent_confirm_outcome", "{\"call_id\":\"write\",\"verification_call_id\":\"old-read\",\"evidence\":\"stale\"}", "bad-check");
            case 4 -> call("inspect", "{}", "fresh-read");
            case 5 -> call("agent_confirm_outcome", "{\"call_id\":\"write\",\"verification_call_id\":\"fresh-read\",\"evidence\":\"actual state checked\"}", "good-check");
            case 6 -> call("agent_report_completion", "{\"status\":\"completed\",\"evidence\":\"verified\"}", "report");
            default -> answer("已核验");
        });
        tasks.call(attach(model, new ToolCallback[]{read, write}, new ToolCallback[]{read, write}));
        var row = store.list("user", "session").get(0);
        assertThat(row).containsEntry("status", "completed");
        var saved = store.snapshot(store.get("user", row.get("id").toString()));
        assertThat(saved.get("outcomes").toString()).contains("INVALID_VERIFICATION");
        assertThat((Map<?,?>) saved.get("unknownActions")).isEmpty();
    }

    private static ToolCallback tool(String name, Function<String, String> action) {
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description(name).inputSchema("{\"type\":\"object\",\"properties\":{}}").build();
            }
            @Override public String call(String input) { return action.apply(input); }
        };
    }
    private static ChatModel scripted(Function<Prompt, AssistantMessage> next) {
        return new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(next.apply(prompt))),
                    org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
                        .usage(new org.springframework.ai.chat.metadata.DefaultUsage(10, 2)).build());
            }
            @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.defer(() -> Flux.just(call(prompt))); }
        };
    }
    private static AssistantMessage call(String name, String args, String id) {
        return AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, args))).build();
    }
    private static AssistantMessage answer(String text) { return new AssistantMessage(text); }
    private static void assertPaired(List<Message> messages) {
        Set<String> pending = new HashSet<>();
        for (Message message : messages) {
            if (message instanceof AssistantMessage assistant) assistant.getToolCalls().forEach(c -> pending.add(c.id()));
            if (message instanceof ToolResponseMessage result) result.getResponses().forEach(r -> assertThat(pending.remove(r.id())).isTrue());
        }
        assertThat(pending).isEmpty();
    }
}
