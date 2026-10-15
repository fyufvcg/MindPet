package service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import service.v3.SecretSanitizer;

import java.util.*;

/** Durable execution checkpoints, independent of conversation/long-term memory. */
@Service
public class AgentTaskStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public AgentTaskStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS agent_task (
              id TEXT PRIMARY KEY, user_id TEXT NOT NULL, session_id TEXT NOT NULL,
              request_id TEXT NOT NULL, status TEXT NOT NULL, goal TEXT NOT NULL,
              snapshot TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)
            """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_agent_task_session ON agent_task(user_id, session_id, updated_at)");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS agent_tool_output (
              task_id TEXT NOT NULL, call_id TEXT NOT NULL, content TEXT NOT NULL,
              PRIMARY KEY(task_id, call_id))
            """);
        jdbc.update("UPDATE agent_task SET status='interrupted' WHERE status='running'");
    }

    public void save(String id, String user, String session, String request, String status,
                     String goal, Map<String, Object> snapshot) {
        try {
            long now = System.currentTimeMillis();
            jdbc.update("""
                INSERT INTO agent_task(id,user_id,session_id,request_id,status,goal,snapshot,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET
                request_id=excluded.request_id,status=excluded.status,snapshot=excluded.snapshot,updated_at=excluded.updated_at
                """, id, user, session, request, status, SecretSanitizer.sanitize(goal),
                SecretSanitizer.sanitize(json.writeValueAsString(snapshot)), now, now);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("无法保存任务检查点", e);
        }
    }

    public Map<String, Object> get(String user, String id) {
        var rows = jdbc.queryForList("SELECT * FROM agent_task WHERE user_id=? AND id=?", user, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("任务不存在或不属于当前用户");
        return rows.get(0);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> snapshot(Map<String, Object> row) {
        try { return json.readValue((String) row.get("snapshot"), Map.class); }
        catch (Exception e) { throw new IllegalStateException("任务检查点无法读取", e); }
    }

    public List<Map<String, Object>> list(String user, String session) {
        return jdbc.queryForList("""
            SELECT id,session_id,request_id,status,goal,created_at,updated_at FROM agent_task
            WHERE user_id=? AND (?='' OR session_id=?) ORDER BY updated_at DESC LIMIT 100
            """, user, session, session);
    }

    public void output(String task, String call, String content) {
        jdbc.update("INSERT OR REPLACE INTO agent_tool_output(task_id,call_id,content) VALUES(?,?,?)",
            task, call, SecretSanitizer.sanitize(content));
    }

    public String readOutput(String task, String call, int offset, int length) {
        var rows = jdbc.queryForList("SELECT content FROM agent_tool_output WHERE task_id=? AND call_id=?", task, call);
        if (rows.isEmpty()) return "该任务没有这个输出。";
        String value = (String) rows.get(0).get("content");
        int start = Math.min(Math.max(0, offset), value.length());
        int end = Math.min(value.length(), start + Math.min(Math.max(1, length), 16000));
        return "offset=" + start + ", end=" + end + ", total=" + value.length() + "\n" + value.substring(start, end);
    }
}
