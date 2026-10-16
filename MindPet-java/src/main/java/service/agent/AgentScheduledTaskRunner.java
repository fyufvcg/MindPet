package service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import service.AiService;
import service.SqliteMemoryService;
import tool.ToolUserContext;
import java.util.*;

/** Scheduled conversations use the same Spring AI task lifecycle as interactive conversations. */
@Service
public class AgentScheduledTaskRunner {
    public record Result(String status, String message, String taskId, Map<String,Object> usage) {}
    private final AiService ai;
    private final AgentTaskService tasks;
    private final AgentTaskStore store;
    private final SqliteMemoryService memory;
    private final ObjectMapper json = new ObjectMapper();
    public AgentScheduledTaskRunner(AiService ai, AgentTaskService tasks, AgentTaskStore store, SqliteMemoryService memory) {
        this.ai = ai; this.tasks = tasks; this.store = store; this.memory = memory;
    }
    public Result execute(AgentScheduleStore.Claim claim) {
        boolean lease = false;
        try {
            if ("memory-prune".equals(claim.config().get("kind")))
                return new Result("success", "记忆维护完成，处理 " + memory.prune("desktop-user") + " 条记录。", "", Map.of());
            tasks.beginSessionRequest("desktop-user", claim.session()); lease = true;
            ToolUserContext.set("desktop-user", claim.session()); ToolUserContext.setRequestId(claim.request());
            ToolUserContext.setExecution(Map.of("workspacePath", Objects.toString(claim.config().get("workspacePath"), ""),
                "selectedMcpServerIds", json.writeValueAsString(claim.config().getOrDefault("selectedMcpServerIds", List.of()))));
            var response = ai.chat("desktop-user", "执行定时任务：" + claim.config().get("action"));
            var records = store.list("desktop-user", claim.session());
            if (records.isEmpty()) return new Result("failed", response.reply(), "", Map.of());
            var record = records.get(0); String id = record.get("id").toString();
            var snapshot = store.snapshot(store.get("desktop-user", id));
            Map<String,Object> usage = new LinkedHashMap<>();
            for (String key : List.of("promptTokens", "completionTokens")) usage.put(key, snapshot.getOrDefault(key, 0));
            usage.put("estimated", response.estimated());
            String state = record.get("status").toString();
            return new Result("completed".equals(state) ? "success" : "failed",
                "completed".equals(state) ? response.reply() : "任务状态：" + state + "。" + response.reply(), id, usage);
        } catch (Exception e) { return new Result("failed", Objects.toString(e.getMessage(), "执行失败"), "", Map.of()); }
        finally { if (lease) tasks.endSessionRequest("desktop-user", claim.session()); ToolUserContext.clear(); }
    }
}
