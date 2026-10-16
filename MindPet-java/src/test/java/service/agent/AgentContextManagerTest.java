package service.agent;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.sqlite.SQLiteDataSource;
import service.DynamicChatClientFactory;
import tool.ToolUserContext;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real SQLite and token budgets, scripted Spring AI summary model; never calls an external service. */
class AgentContextManagerTest {
    @TempDir Path temp;
    JdbcTemplate jdbc;
    AgentContextStore archive;
    AgentContextManager contexts;
    DynamicChatClientFactory clients;
    AtomicInteger summaries;
    static final String SUMMARY = "{\"goal\":\"处理文件\",\"constraints\":[\"不要修改记忆系统\"],\"decisions\":[],\"completed\":[],\"evidence\":[],\"pending\":[],\"failures\":[],\"artifacts\":[]}";

    @BeforeEach void setup() {
        var database = new SQLiteDataSource(); database.setUrl("jdbc:sqlite:" + temp.resolve("context.db"));
        jdbc = new JdbcTemplate(database);
        new AgentTaskStore(jdbc);
        jdbc.execute("CREATE TABLE sessions(user_id TEXT,id TEXT,context_summary TEXT,updated_at TEXT)");
        jdbc.execute("CREATE TABLE session_messages(user_id TEXT,session_id TEXT,message_id TEXT,payload_json TEXT,sequence INTEGER)");
        archive = new AgentContextStore(jdbc);
        clients = mock(DynamicChatClientFactory.class);
        when(clients.effectiveModel()).thenReturn("fixture");
        contexts = new AgentContextManager(archive, clients);
        ReflectionTestUtils.setField(contexts, "maxInput", 6000);
        summaries = new AtomicInteger(); summaryModel(p -> SUMMARY);
        ToolUserContext.set("user", "session"); ToolUserContext.setRequestId("current");
    }
    @AfterEach void cleanup() { ToolUserContext.clear(); }
    void summaryModel(Function<Prompt,String> action) {
        ChatModel model = new ChatModel() {
            public ChatResponse call(Prompt prompt) {
                summaries.incrementAndGet();
                return new ChatResponse(List.of(new Generation(new AssistantMessage(action.apply(prompt)))),
                    org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
                        .usage(new org.springframework.ai.chat.metadata.DefaultUsage(50, 20)).build());
            }
        };
        when(clients.buildBackground(anyLong())).thenReturn(ChatClient.builder(model).build());
    }
    List<Message> oversized() {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("固定规则"));
        for (int i = 0; i < 8; i++) messages.add(new UserMessage("历史 " + i + " 数据".repeat(1000)));
        messages.add(new UserMessage("当前目标"));
        messages.add(AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("c", "function", "read", "{}"))).build());
        messages.add(ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("c", "read", "文件已经读取"))).build());
        return messages;
    }
    @Test void historyIsReadWithoutAnEarlySummaryAndCheckpointPersistsAtAssembly() {
        for (int i = 0; i < 8; i++) archive.append("user", "session", "m" + i, "user", "历史 " + i + " 数据".repeat(1000));
        var history = contexts.history("user", "session");
        assertThat(summaries).hasValue(0);
        List<Message> source = new ArrayList<>(List.of(new SystemMessage("规则")));
        source.addAll(history); source.add(new UserMessage("当前目标"));
        var result = contexts.assemble(source, List.of(), "当前目标", "", u -> {}, 5000);
        assertThat(result.afterTokens()).isLessThanOrEqualTo(contexts.inputBudget());
        assertThat(summaries.get()).isPositive();
        assertThat(archive.latest("user", "session").get("summary")).isEqualTo(SUMMARY);
    }
    @Test void validContextNeverCallsTheSummaryModel() {
        var source = List.<Message>of(new SystemMessage("规则"), new UserMessage("当前目标"));
        assertThat(contexts.assemble(source, List.of(), "当前目标", "", u -> {}, 5000).messages()).isEqualTo(source);
        assertThat(summaries).hasValue(0);
    }
    @Test void malformedSummaryFallsBackWithoutOrphanToolResponses() {
        summaryModel(p -> "not JSON");
        var costs = new ArrayList<AgentContextManager.SummaryUsage>();
        var result = contexts.assemble(oversized(), List.of(), "当前目标", "", costs::add, 5000);
        assertThat(result.afterTokens()).isLessThanOrEqualTo(contexts.inputBudget());
        assertThat(result.messages().toString()).contains("摘要暂不可用", "当前目标", "文件已经读取");
        assertThat(costs).hasSize(1);
        var last = result.messages().get(result.messages().size() - 1);
        assertThat(last).isInstanceOf(ToolResponseMessage.class);
        assertThat(result.messages().get(result.messages().size() - 2)).isInstanceOf(AssistantMessage.class);
    }
    @Test void networkFailureUsesAnExplicitEstimateAndStillBuildsAMainPrompt() {
        summaryModel(p -> { throw new IllegalStateException("timeout"); });
        var costs = new ArrayList<AgentContextManager.SummaryUsage>();
        var result = contexts.assemble(oversized(), List.of(), "当前目标", "", costs::add, 5000);
        assertThat(result.messages().toString()).contains("摘要暂不可用");
        assertThat(costs).hasSize(1);
        assertThat(costs.get(0).estimated()).isTrue();
        assertThat(costs.get(0).completionTokens()).isPositive();
    }
    @Test void insufficientSummaryBudgetDoesNotSpendAnotherModelCall() {
        var result = contexts.assemble(oversized(), List.of(), "当前目标", "", u -> {}, 5000, (i,o) -> false);
        assertThat(summaries).hasValue(0);
        assertThat(result.afterTokens()).isLessThanOrEqualTo(contexts.inputBudget());
    }
    @Test void protectedCurrentInputCannotBeSilentlyDropped() {
        var messages = new ArrayList<>(oversized());
        messages.add(new UserMessage("附件 " + "正文".repeat(12000)));
        assertThatThrownBy(() -> contexts.assemble(messages, List.of(), messages.get(messages.size()-1).getText(), "", u -> {}, 5000))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("超过输入预算");
        assertThat(summaries).hasValue(0);
    }
    @Test void clearRemovesRuntimeAndUiDataButNeverLongTermMemory() {
        jdbc.execute("CREATE TABLE memory_sentinel(value TEXT)"); jdbc.update("INSERT INTO memory_sentinel VALUES('keep')");
        jdbc.update("INSERT INTO sessions VALUES('user','session','old summary','now')");
        jdbc.update("INSERT INTO session_messages VALUES('user','session','old','{}',1)");
        archive.append("user", "session", "old", "user", "不要修改记忆系统");
        long sequence = ((Number)archive.history("user", "session", 0, "").get(0).get("sequence")).longValue();
        archive.contract("user", "session", "memory", sequence, "不要修改记忆系统");
        new AgentTaskStore(jdbc).save("task", "user", "session", "r", "failed", "goal", Map.of());
        archive.clearConversation("user", "session");
        assertThat(archive.history("user", "session", 0, "")).isEmpty();
        assertThat(archive.contracts("user", "session")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM session_messages", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent_task", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT value FROM memory_sentinel", String.class)).isEqualTo("keep");
        assertThat(archive.latest("user", "session").get("summary")).isEqualTo("");
    }
    @Test void clearAndUserEditsFenceAnInFlightCheckpoint() {
        archive.checkpoint("user", "session", 1, "old");
        long id = ((Number)archive.latest("user", "session").get("id")).longValue();
        archive.clear("user", "session");
        archive.checkpointIfCurrent("user", "session", id, 10, "stale");
        assertThat(archive.latest("user", "session").get("summary")).isEqualTo("");
    }
    @Test void contractRequiresAQuoteFromAnActualUserMessageAndSupportsCorrections() {
        archive.append("user", "session", "user1", "user", "不要修改记忆系统");
        long sequence = ((Number)archive.history("user", "session", 0, "").get(0).get("sequence")).longValue();
        assertThatThrownBy(() -> archive.contract("user", "session", "memory", sequence, "可以删除记忆"))
            .isInstanceOf(IllegalArgumentException.class);
        archive.contract("user", "session", "memory", sequence, "不要修改记忆系统");
        assertThat(archive.contracts("user", "session")).hasSize(1);
        archive.contract("user", "session", "memory", 0, "");
        assertThat(archive.contracts("user", "session")).isEmpty();
    }
    @Test void historySearchIsScopedAndCanPageInsideALargeRecord() throws Exception {
        archive.append("user", "session", "first", "user", "MARKER " + "资料".repeat(5000) + " END");
        archive.append("other", "session", "private", "user", "MARKER private");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var first = mapper.readTree(archive.read("user", "session", "marker", 0, 1, 0));
        assertThat(first.path("messages").size()).isEqualTo(1);
        var row = first.path("messages").get(0);
        assertThat(row.path("truncated").asBoolean()).isTrue();
        long seq = row.path("sequence").asLong();
        var next = mapper.readTree(archive.read("user", "session", "marker", seq - 1, 1, row.path("nextOffset").asInt()));
        assertThat(next.path("messages").get(0).path("offset").asInt()).isEqualTo(row.path("nextOffset").asInt());
        assertThat(next.toString()).doesNotContain("private");
    }
    @Test void truncatedTailAlwaysIncludesTheActualEndAndPreservesUnicode() {
        String input = "文档🙂".repeat(5000) + "真正结尾🙂";
        String tail = ContextTokens.tail(input, 100);
        assertThat(tail).endsWith("真正结尾🙂");
        assertThat(ContextTokens.count(tail)).isLessThanOrEqualTo(100);
        assertThat(Character.isLowSurrogate(tail.charAt(0))).isFalse();
    }

    @Test void editingTheSourceInvalidatesAPinnedQuoteAndItsOldCheckpoint() {
        archive.append("user", "session", "u", "user", "使用方案 A");
        long sequence = ((Number)archive.history("user", "session", 0, "").get(0).get("sequence")).longValue();
        archive.contract("user", "session", "solution", sequence, "使用方案 A");
        archive.checkpoint("user", "session", sequence, "旧方案");
        archive.append("user", "session", "u", "user", "改用方案 B");
        assertThat(archive.contracts("user", "session")).isEmpty();
        assertThat(archive.latest("user", "session").get("summary")).isEqualTo("");
    }
    @Test void budgetWarningCannotReplaceTheLatestToolEvidenceDuringCompaction() {
        List<Message> messages = new ArrayList<>(List.of(new SystemMessage("规则"), new UserMessage("当前目标")));
        for (int i = 0; i < 8; i++) {
            messages.add(AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("c" + i, "function", "read", "{}"))).build());
            messages.add(ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("c" + i, "read", "第" + i + "份证据 " + "资料".repeat(900)))).build());
        }
        messages.add(new UserMessage("[任务预算提示] 尽快完成"));
        var result = contexts.assemble(messages, List.of(), "当前目标", "", u -> {}, 5000);
        assertThat(result.messages().stream().filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast)
            .flatMap(m -> m.getResponses().stream()).map(ToolResponseMessage.ToolResponse::responseData)).anyMatch(s -> s.contains("第7份证据"));
        assertThat(result.afterTokens()).isLessThanOrEqualTo(contexts.inputBudget());
    }
    @Test void clearHttpEndpointsRejectActiveTasksAndScopeAllToTheCurrentUser() throws Exception {
        var tasks = new AgentTaskService(new AgentTaskStore(jdbc), mock(service.McpManager.class));
        try {
            var controller = new controller.SessionController(mock(service.SessionService.class));
            controller.setRuntimeContext(archive, tasks);
            var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
            archive.append("user", "session", "u", "user", "current data");
            archive.append("user", "second", "u", "user", "second data");
            archive.append("other", "session", "u", "user", "private data");
            tasks.beginSessionRequest("user", "session");
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/desktop/sessions/session/messages?userId=user"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
            assertThat(archive.history("user", "session", 0, "")).hasSize(1);
            tasks.endSessionRequest("user", "session");
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/desktop/sessions/session/messages?userId=user"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
            assertThat(archive.history("user", "session", 0, "")).isEmpty();
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/desktop/sessions/messages?userId=user"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
            assertThat(archive.history("user", "second", 0, "")).isEmpty();
            assertThat(archive.history("other", "session", 0, "")).hasSize(1);
        } finally { tasks.close(); }
    }
}
