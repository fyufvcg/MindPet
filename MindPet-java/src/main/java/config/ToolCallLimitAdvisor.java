package config;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import util.Logger;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 每工具最多调用 2 次。用 ThreadLocal 计数。
 * 不读 request 里的工具列表（Spring AI 1.0 跨迭代拿不到），
 * 改为在 LLM 返回后检查计数：超限的工具从下一轮移除。
 */
public class ToolCallLimitAdvisor implements CallAdvisor {

    private static final int MAX_PER_TOOL = 2;
    private static final ThreadLocal<Map<String, Integer>> COUNTS = new ThreadLocal<>();

    private final Logger logger;

    public ToolCallLimitAdvisor(Logger logger) {
        this.logger = logger;
    }

    public static void reset() {
        COUNTS.remove();
    }

    private Map<String, Integer> counts() {
        Map<String, Integer> m = COUNTS.get();
        if (m == null) { m = new HashMap<>(); COUNTS.set(m); }
        return m;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        Map<String, Integer> cnt = counts();
        logger.log("DEBUG", "  [限流advisor] 当前计数: " + cnt);

        // 如果任何工具已达上限，去掉它
        List<String> blocked = cnt.entrySet().stream()
            .filter(e -> e.getValue() >= MAX_PER_TOOL)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());

        ChatClientRequest processed = request;
        if (!blocked.isEmpty()) {
            logger.log("INFO", "  [限流] 屏蔽已达" + MAX_PER_TOOL + "次的工具: " + String.join(", ", blocked));
            List<org.springframework.ai.tool.ToolCallback> currentCbs = getEffectiveCallbacks(request);
            List<org.springframework.ai.tool.ToolCallback> allowed = currentCbs.stream()
                .filter(tc -> !blocked.contains(tc.getToolDefinition().name()))
                .collect(Collectors.toList());
            if (allowed.isEmpty()) {
                logger.log("WARN", "所有工具均已超限，强制文本回复");
                processed = stripAllTools(request);
            } else if (allowed.size() < currentCbs.size()) {
                processed = withCallbacks(request, allowed);
            }
        }

        ChatClientResponse response = chain.nextCall(processed);

        // 计数
        for (AssistantMessage.ToolCall tc : extractToolCalls(response)) {
            String name = tc.name();
            int n = cnt.getOrDefault(name, 0) + 1;
            cnt.put(name, n);
            logger.log("INFO", "  [" + name + " 第" + n + "/" + MAX_PER_TOOL + "次]");
        }

        return response;
    }

    @Override public String getName() { return "toolCallLimit"; }
    @Override public int getOrder() { return 100; }

    // ---- helpers ----

    private List<AssistantMessage.ToolCall> extractToolCalls(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null) return List.of();
        List<Generation> results = response.chatResponse().getResults();
        if (results == null) return List.of();
        List<AssistantMessage.ToolCall> all = new ArrayList<>();
        for (Generation gen : results) {
            if (gen.getOutput() instanceof AssistantMessage) {
                AssistantMessage msg = (AssistantMessage) gen.getOutput();
                List<AssistantMessage.ToolCall> tcs = msg.getToolCalls();
                if (tcs != null) all.addAll(tcs);
            }
        }
        return all;
    }

    private List<org.springframework.ai.tool.ToolCallback> getEffectiveCallbacks(ChatClientRequest request) {
        if (request.prompt() == null || request.prompt().getOptions() == null) return List.of();
        if (request.prompt().getOptions() instanceof ToolCallingChatOptions) {
            ToolCallingChatOptions opts = (ToolCallingChatOptions) request.prompt().getOptions();
            List<org.springframework.ai.tool.ToolCallback> cbs = opts.getToolCallbacks();
            return cbs != null ? cbs : List.of();
        }
        return List.of();
    }

    private ChatClientRequest withCallbacks(ChatClientRequest request,
                                            List<org.springframework.ai.tool.ToolCallback> callbacks) {
        ToolCallingChatOptions opts = ToolCallingChatOptions.builder()
            .toolCallbacks(callbacks).build();
        return request.mutate()
            .prompt(new org.springframework.ai.chat.prompt.Prompt(
                request.prompt().getInstructions(), opts))
            .context(request.context()).build();
    }

    private ChatClientRequest stripAllTools(ChatClientRequest request) {
        return withCallbacks(request, List.of());
    }
}
