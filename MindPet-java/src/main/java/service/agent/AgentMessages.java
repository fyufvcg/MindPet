package service.agent;

import org.springframework.ai.chat.messages.*;
import java.util.*;

/** Explicit wire format preserves tool call/result pairing across process restarts. Images stay transient. */
public final class AgentMessages {
    private AgentMessages() {}

    public static List<Map<String, Object>> encode(List<Message> messages) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Message message : messages) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("role", message.getMessageType().getValue());
            row.put("text", Objects.toString(message.getText(), ""));
            if (Boolean.TRUE.equals(message.getMetadata().get("mindpet.observation"))) row.put("observation", true);
            if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
                row.put("calls", assistant.getToolCalls().stream().map(c -> Map.of(
                    "id", c.id(), "name", c.name(), "arguments", Objects.toString(c.arguments(), "{}"))).toList());
            }
            if (message instanceof ToolResponseMessage result) {
                row.put("responses", result.getResponses().stream().map(r -> Map.of(
                    "id", r.id(), "name", r.name(), "data", r.responseData())).toList());
            }
            if (message instanceof UserMessage user && !user.getMedia().isEmpty())
                row.put("text", row.get("text") + "\n[先前图片未保存到检查点；需要时重新读取文件或截图。]");
            rows.add(row);
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    public static List<Message> decode(Object payload) {
        List<Message> messages = new ArrayList<>();
        if (!(payload instanceof List<?> rows)) return messages;
        for (Object value : rows) {
            Map<String, Object> row = (Map<String, Object>) value;
            String text = Objects.toString(row.get("text"), "");
            switch (Objects.toString(row.get("role"), "")) {
                case "system" -> messages.add(new SystemMessage(text));
                case "user" -> messages.add(UserMessage.builder().text(text)
                    .metadata(Map.of("mindpet.observation", Boolean.TRUE.equals(row.get("observation")))).build());
                case "assistant" -> {
                    List<Map<String, Object>> calls = (List<Map<String, Object>>) row.getOrDefault("calls", List.of());
                    messages.add(AssistantMessage.builder().content(text).toolCalls(calls.stream().map(c ->
                        new AssistantMessage.ToolCall((String)c.get("id"), "function", (String)c.get("name"),
                            (String)c.get("arguments"))).toList()).build());
                }
                case "tool" -> {
                    List<Map<String, Object>> results = (List<Map<String, Object>>) row.get("responses");
                    messages.add(ToolResponseMessage.builder().responses(results.stream().map(r ->
                        new ToolResponseMessage.ToolResponse((String)r.get("id"), (String)r.get("name"),
                            (String)r.get("data"))).toList()).build());
                }
                default -> throw new IllegalArgumentException("未知任务消息类型");
            }
        }
        return messages;
    }

    /** An interrupted side effect may have happened: acknowledge it as unknown, never replay it. */
    public static void closePendingCalls(List<Message> messages) {
        closePendingCalls(messages, Map.of());
    }

    public static void closePendingCalls(List<Message> messages, Map<String, String> outcomes) {
        List<Message> repaired = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (message instanceof ToolResponseMessage) throw new IllegalStateException("工具结果缺少紧邻的调用记录，不能安全恢复");
            repaired.add(message);
            if (!(message instanceof AssistantMessage assistant) || !assistant.hasToolCalls()) continue;
            Map<String,ToolResponseMessage.ToolResponse> known = new LinkedHashMap<>();
            while (i + 1 < messages.size() && messages.get(i + 1) instanceof ToolResponseMessage result) {
                i++;
                result.getResponses().forEach(r -> known.put(r.id(), r));
            }
            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
            for (var call : assistant.getToolCalls()) responses.add(known.getOrDefault(call.id(),
                new ToolResponseMessage.ToolResponse(call.id(), call.name(), outcomes.getOrDefault(call.id(),
                    "{\"success\":false,\"code\":\"INTERRUPTED_UNKNOWN_OUTCOME\",\"message\":\"执行中断，操作可能已经发生。先观察实际状态，不要盲目重复写操作。\"}"))));
            repaired.add(ToolResponseMessage.builder().responses(responses).build());
        }
        messages.clear(); messages.addAll(repaired);
    }
}
