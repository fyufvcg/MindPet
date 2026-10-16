package service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;

/** SQLite owns schedule configuration, claims and logs. UI copies never overwrite runtime state. */
@Repository
public class AgentScheduleStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();
    private final TransactionTemplate transactions;
    public AgentScheduleStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.transactions = new TransactionTemplate(manager);
        jdbc.execute("CREATE TABLE IF NOT EXISTS agent_schedule(id TEXT PRIMARY KEY, config TEXT NOT NULL, next_run INTEGER NOT NULL, running INTEGER NOT NULL DEFAULT 0, trigger_count INTEGER NOT NULL DEFAULT 0, last_triggered TEXT NOT NULL DEFAULT '', logs TEXT NOT NULL DEFAULT '[]')");
        jdbc.execute("CREATE TABLE IF NOT EXISTS agent_schedule_meta(id INTEGER PRIMARY KEY)");
        for (var row : jdbc.queryForList("SELECT * FROM agent_schedule WHERE running=1")) {
            List<Map<String,Object>> logs = logs(row);
            for (var log : logs) if ("running".equals(log.get("status"))) {
                log.put("status", "failed"); log.put("message", "应用退出导致执行中断；请核对实际结果后在任务记录中恢复，未自动重放。");
            }
            var config = decode(row.get("config")); config.put("isActive", false);
            jdbc.update("UPDATE agent_schedule SET running=0,config=?,logs=? WHERE id=?", encode(config), encode(logs), row.get("id"));
        }
    }
    public synchronized List<Map<String,Object>> list() {
        return jdbc.queryForList("SELECT * FROM agent_schedule ORDER BY id").stream().map(row -> {
            Map<String,Object> result = decode(row.get("config"));
            result.put("id", row.get("id")); result.put("nextRunAt", row.get("next_run"));
            result.put("running", ((Number)row.get("running")).intValue() == 1);
            result.put("triggerCount", row.get("trigger_count")); result.put("lastTriggered", row.get("last_triggered"));
            result.put("logs", logs(row)); return result;
        }).toList();
    }
    public synchronized void replace(List<Map<String,Object>> input, boolean migration) {
        if (migration && !jdbc.queryForList("SELECT id FROM agent_schedule_meta").isEmpty()) return;
        // Validate the whole batch before changing any rows.
        var valid = input.stream().filter(t -> !Boolean.TRUE.equals(t.get("isSystem"))).toList();
        Set<String> ids = new HashSet<>();
        for (var task : valid) {
            String id = Objects.toString(task.get("id"), "");
            double interval = task.get("interval") instanceof Number n ? n.doubleValue() : 0;
            if (id.isBlank() || !ids.add(id) || !Double.isFinite(interval) || interval != Math.rint(interval) || interval < (migration ? 1 : 5) || interval > 31536000
                || Objects.toString(task.get("action"), "").isBlank()) throw new IllegalArgumentException("定时任务参数无效");
            if ((task.containsKey("isActive") && !(task.get("isActive") instanceof Boolean))
                || (task.containsKey("workspacePath") && !(task.get("workspacePath") instanceof String))
                || (task.containsKey("kind") && !Set.of("agent", "memory-prune").contains(task.get("kind"))))
                throw new IllegalArgumentException("定时任务配置类型无效");
            if (task.containsKey("selectedMcpServerIds") && (!(task.get("selectedMcpServerIds") instanceof List<?> servers)
                || servers.stream().anyMatch(value -> !(value instanceof String idValue) || idValue.isBlank())))
                throw new IllegalArgumentException("定时任务 MCP 范围无效");
        }
        transactions.executeWithoutResult(status -> {
            for (var row : jdbc.queryForList("SELECT id FROM agent_schedule WHERE running=1"))
                if (!ids.contains(row.get("id"))) throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT, "请先在任务记录中停止正在执行的定时任务，再删除计划。");
            for (var task : valid) {
                String id = task.get("id").toString();
                Map<String,Object> config = new LinkedHashMap<>();
                for (String key : List.of("name", "interval", "isActive", "action", "workspacePath", "selectedMcpServerIds", "kind"))
                    if (task.containsKey(key)) config.put(key, task.get(key));
                if (migration) config.put("interval", Math.max(5, ((Number)config.get("interval")).longValue()));
                if (migration && "系统画像提纯与经验沉淀".equals(task.get("name"))) config.put("kind", "memory-prune");
                long next = System.currentTimeMillis() + ((Number)config.get("interval")).longValue()*1000;
                var existing = jdbc.queryForList("SELECT config FROM agent_schedule WHERE id=?", id);
                if (existing.isEmpty()) {
                    List<Map<String,Object>> previous = new ArrayList<>();
                    if (migration && task.get("logs") instanceof List<?> imported) for (Object item : imported.stream().limit(100).toList()) {
                        if (!(item instanceof Map<?,?> entry)) continue;
                        Map<String,Object> log = new LinkedHashMap<>(); entry.forEach((key,value) -> log.put(key.toString(),value));
                        if ("running".equals(log.get("status"))) {
                            log.put("status", "failed"); log.put("message", "迁移前的执行未确认完成，请核对结果后继续。"); config.put("isActive", false);
                        }
                        previous.add(log);
                    }
                    int count = migration && task.get("triggerCount") instanceof Number n ? Math.max(0,n.intValue()) : 0;
                    jdbc.update("INSERT INTO agent_schedule(id,config,next_run,trigger_count,last_triggered,logs) VALUES(?,?,?,?,?,?)", id, encode(config), next,
                        count, migration ? Objects.toString(task.get("lastTriggered"), "") : "", service.v3.SecretSanitizer.sanitize(encode(previous)));
                }
                else {
                    Map<String,Object> old = decode(existing.get(0).get("config"));
                    boolean reset = !Objects.equals(old.get("interval"), config.get("interval"))
                        || !Objects.equals(old.get("isActive"), config.get("isActive"));
                    jdbc.update("UPDATE agent_schedule SET config=?,next_run=CASE WHEN ? THEN ? ELSE next_run END WHERE id=?", encode(config), reset, next, id);
                }
            }
            for (var row : jdbc.queryForList("SELECT id FROM agent_schedule"))
                if (!ids.contains(row.get("id"))) jdbc.update("DELETE FROM agent_schedule WHERE id=?", row.get("id"));
            jdbc.update("INSERT OR IGNORE INTO agent_schedule_meta(id) VALUES(1)");
        });
    }
    public synchronized void upsert(String id, Map<String,Object> updates) {
        var current = new ArrayList<>(list());
        Map<String,Object> task = current.stream().filter(t -> id.equals(t.get("id"))).findFirst().orElse(new LinkedHashMap<>());
        current.removeIf(t -> id.equals(t.get("id")));
        task.putAll(updates); task.put("id", id); current.add(task);
        replace(current, false);
    }
    public synchronized void delete(String id) {
        var current = new ArrayList<>(list());
        if (!current.removeIf(t -> id.equals(t.get("id")))) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "定时任务不存在");
        replace(current, false);
    }
    public synchronized void clearLogs(String id) {
        var rows = jdbc.queryForList("SELECT * FROM agent_schedule WHERE id=?", id);
        if (rows.isEmpty()) return;
        jdbc.update("UPDATE agent_schedule SET logs=? WHERE id=?", encode(logs(rows.get(0)).stream()
            .filter(log -> "running".equals(log.get("status"))).toList()), id);
    }
    public record Claim(String id, String session, String request, Map<String,Object> config) {}
    public synchronized List<Claim> claimDue(long now) {
        List<Claim> claimed = new ArrayList<>();
        transactions.executeWithoutResult(tx -> {
            for (var row : jdbc.queryForList("SELECT * FROM agent_schedule WHERE running=0 AND json_extract(config,'$.isActive')=1 AND next_run<=? ORDER BY next_run LIMIT 16", now)) {
                var config = decode(row.get("config"));
                if (!Boolean.TRUE.equals(config.get("isActive"))) continue;
                String id = row.get("id").toString(), run = UUID.randomUUID().toString();
                long next = now + ((Number)config.get("interval")).longValue()*1000;
                String session = "cron:" + id + ":" + run;
                var log = new LinkedHashMap<String,Object>(Map.of("id", run, "time", Instant.ofEpochMilli(now).toString(),
                    "status", "running", "message", "正在后台执行", "sessionId", session));
                log.put("messages", List.of(Map.of("id", run+"-user", "sender", "user", "text", config.get("action"), "time", log.get("time")),
                    Map.of("id", run+"-agent", "sender", "agent", "text", "", "isThinking", true, "time", log.get("time"))));
                var logs = logs(row); logs.add(0, log); if (logs.size()>100) logs = new ArrayList<>(logs.subList(0,100));
                int count = jdbc.update("UPDATE agent_schedule SET running=1,next_run=?,trigger_count=trigger_count+1,last_triggered=?,logs=? WHERE id=? AND running=0", next, log.get("time"), encode(logs), id);
                if (count == 1) claimed.add(new Claim(id, session, run, config));
            }
        });
        return claimed;
    }
    public synchronized boolean finish(String id, String run, AgentScheduledTaskRunner.Result result) {
        var rows = jdbc.queryForList("SELECT * FROM agent_schedule WHERE id=?", id);
        if (rows.isEmpty()) return false;
        var row = rows.get(0); var logs = logs(row);
        if (((Number)row.get("running")).intValue() != 1 || logs.stream().noneMatch(log -> run.equals(log.get("id")) && "running".equals(log.get("status")))) return false;
        for (var log : logs) if (run.equals(log.get("id"))) {
            log.put("status", result.status()); log.put("message", service.v3.SecretSanitizer.sanitize(result.message()));
            log.put("taskId", result.taskId()); log.put("usage", result.usage());
            log.put("messages", List.of(Map.of("id", run+"-agent", "sender", "agent", "text", service.v3.SecretSanitizer.sanitize(result.message()), "time", log.getOrDefault("time", ""), "isThinking", false)));
        }
        long next = System.currentTimeMillis() + ((Number)decode(row.get("config")).get("interval")).longValue()*1000;
        var config = decode(row.get("config"));
        if (!"success".equals(result.status())) config.put("isActive", false);
        jdbc.update("UPDATE agent_schedule SET running=0,next_run=?,logs=?,config=? WHERE id=?", next, encode(logs), encode(config), id);
        return true;
    }
    private Map<String,Object> decode(Object value) {
        try { return json.readValue(value.toString(), new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){}); }
        catch (Exception e) { throw new IllegalStateException("定时任务配置无法读取", e); }
    }
    private List<Map<String,Object>> logs(Map<String,Object> row) {
        try { return json.readValue(row.get("logs").toString(), new com.fasterxml.jackson.core.type.TypeReference<ArrayList<Map<String,Object>>>(){}); }
        catch (Exception e) { throw new IllegalStateException("定时任务记录无法读取", e); }
    }
    private String encode(Object value) { try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalArgumentException(e); } }
}
