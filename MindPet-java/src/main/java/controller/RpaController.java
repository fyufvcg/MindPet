package controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import util.Logger;

import jakarta.annotation.PostConstruct;
import java.util.*;

/**
 * RPA 运行记录持久化 API — Electron 通过 HTTP 写入 PostgreSQL。
 */
@RestController
@RequestMapping("/api/desktop/rpa")
public class RpaController {

    private final JdbcTemplate jdbc;
    private final Logger logger;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public RpaController(JdbcTemplate jdbc, Logger logger) {
        this.jdbc = jdbc;
        this.logger = logger;
    }

    @PostConstruct
    public void initSchema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS rpa_runs (
                id TEXT PRIMARY KEY,
                workflow_id TEXT,
                workflow_version INTEGER,
                session_id TEXT,
                status TEXT NOT NULL,
                inputs_json TEXT NOT NULL,
                output_json TEXT,
                error_json TEXT,
                created_at BIGINT NOT NULL,
                started_at BIGINT,
                finished_at BIGINT
            )
        """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS rpa_run_events (
                id TEXT PRIMARY KEY,
                run_id TEXT NOT NULL REFERENCES rpa_runs(id) ON DELETE CASCADE,
                sequence INTEGER NOT NULL,
                type TEXT NOT NULL,
                action_json TEXT,
                payload_json TEXT,
                created_at BIGINT NOT NULL,
                UNIQUE (run_id, sequence)
            )
        """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS rpa_artifacts (
                id TEXT PRIMARY KEY,
                run_id TEXT NOT NULL REFERENCES rpa_runs(id) ON DELETE CASCADE,
                event_id TEXT REFERENCES rpa_run_events(id) ON DELETE SET NULL,
                type TEXT NOT NULL,
                file_path TEXT NOT NULL,
                sha256 TEXT,
                created_at BIGINT NOT NULL
            )
        """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_rpa_runs_workflow_created ON rpa_runs(workflow_id, created_at DESC)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_rpa_events_run_seq ON rpa_run_events(run_id, sequence)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_rpa_artifacts_run ON rpa_artifacts(run_id)");
        logger.log("INFO", "[RPA] 数据库表已就绪");
    }

    // ==================== runs ====================

    @PostMapping("/runs")
    public Map<String, Object> createRun(@RequestBody Map<String, Object> body) {
        try {
            String id = str(body, "id", UUID.randomUUID().toString());
            String workflowId = strOrNull(body, "workflowId");
            Integer workflowVersion = intOrNull(body, "workflowVersion");
            String sessionId = strOrNull(body, "sessionId");
            String status = str(body, "status", "pending");
            String inputsJson = toJson(body.getOrDefault("inputs", Map.of()));
            long createdAt = longVal(body, "createdAt", System.currentTimeMillis());

            jdbc.update(
                "INSERT INTO rpa_runs (id, workflow_id, workflow_version, session_id, status, inputs_json, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, workflowId, workflowVersion, sessionId, status, inputsJson, createdAt
            );

            Map<String, Object> result = runToMap(id, workflowId, workflowVersion, sessionId,
                status, inputsJson, null, null, createdAt, null, null);
            logger.log("INFO", "[RPA] 创建运行记录: " + id);
            return Map.of("status", "ok", "run", result);
        } catch (Exception e) {
            logger.log("ERROR", "[RPA] 创建运行记录失败: " + e.getMessage());
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    @GetMapping("/runs/{runId}")
    public Map<String, Object> getRun(@PathVariable String runId) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM rpa_runs WHERE id = ?", runId
            );
            if (rows.isEmpty()) return Map.of("status", "ok", "run", null);
            return Map.of("status", "ok", "run", mapRun(rows.get(0)));
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    @PutMapping("/runs/{runId}/status")
    public Map<String, Object> updateRunStatus(@PathVariable String runId, @RequestBody Map<String, Object> body) {
        try {
            String status = str(body, "status", "pending");
            String outputJson = body.containsKey("output") ? toJson(body.get("output")) : null;
            String errorJson = body.containsKey("error") ? toJson(body.get("error")) : null;
            Long startedAt = longOrNull(body, "startedAt");
            Long finishedAt = longOrNull(body, "finishedAt");

            int updated = jdbc.update(
                "UPDATE rpa_runs SET status = ?"
                    + (outputJson != null ? ", output_json = ?" : "")
                    + (errorJson != null ? ", error_json = ?" : "")
                    + ", started_at = COALESCE(?, started_at)"
                    + ", finished_at = COALESCE(?, finished_at)"
                    + " WHERE id = ?",
                buildParams(status, outputJson, errorJson, startedAt, finishedAt, runId)
            );
            if (updated == 0) return Map.of("status", "error", "message", "运行记录不存在: " + runId);
            return Map.of("status", "ok");
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    // ==================== events ====================

    @PostMapping("/runs/{runId}/events")
    public Map<String, Object> appendEvent(@PathVariable String runId, @RequestBody Map<String, Object> body) {
        try {
            String id = str(body, "id", UUID.randomUUID().toString());
            String type = str(body, "type", "log");
            String actionJson = body.containsKey("action") ? toJson(body.get("action")) : null;
            String payloadJson = body.containsKey("payload") ? toJson(body.get("payload")) : null;
            long createdAt = longVal(body, "createdAt", System.currentTimeMillis());

            // 自增 sequence
            jdbc.update(
                "INSERT INTO rpa_run_events (id, run_id, sequence, type, action_json, payload_json, created_at) "
                    + "SELECT ?, ?, COALESCE(MAX(sequence), 0) + 1, ?, ?, ?, ? FROM rpa_run_events WHERE run_id = ?",
                id, runId, type, actionJson, payloadJson, createdAt, runId
            );

            // 读回完整行
            Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM rpa_run_events WHERE id = ?", id
            );
            return Map.of("status", "ok", "event", mapEvent(row));
        } catch (Exception e) {
            logger.log("ERROR", "[RPA] 追加事件失败: " + e.getMessage());
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    @GetMapping("/runs/{runId}/events")
    public Map<String, Object> listEvents(
            @PathVariable String runId,
            @RequestParam(defaultValue = "0") int afterSequence) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM rpa_run_events WHERE run_id = ? AND sequence > ? ORDER BY sequence ASC",
                runId, afterSequence
            );
            List<Map<String, Object>> events = new ArrayList<>();
            for (var row : rows) events.add(mapEvent(row));
            return Map.of("status", "ok", "events", events);
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    // ==================== artifacts ====================

    @PostMapping("/runs/{runId}/artifacts")
    public Map<String, Object> createArtifact(@PathVariable String runId, @RequestBody Map<String, Object> body) {
        try {
            String id = str(body, "id", UUID.randomUUID().toString());
            String eventId = strOrNull(body, "eventId");
            String type = str(body, "type", "file");
            String filePath = str(body, "filePath", "");
            String sha256 = strOrNull(body, "sha256");
            long createdAt = longVal(body, "createdAt", System.currentTimeMillis());

            jdbc.update(
                "INSERT INTO rpa_artifacts (id, run_id, event_id, type, file_path, sha256, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, runId, eventId, type, filePath, sha256, createdAt
            );
            return Map.of("status", "ok", "artifact",
                Map.of("id", id, "runId", runId, "eventId", eventId != null ? eventId : "",
                    "type", type, "filePath", filePath, "sha256", sha256 != null ? sha256 : "",
                    "createdAt", createdAt));
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    // ==================== helpers ====================

    private String str(Map<String, Object> body, String key, String defaultVal) {
        Object v = body.getOrDefault(key, defaultVal);
        return v != null ? String.valueOf(v) : defaultVal;
    }

    private String strOrNull(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v != null ? String.valueOf(v) : null;
    }

    private Integer intOrNull(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v != null) try { return Integer.parseInt(String.valueOf(v)); } catch (Exception ignored) {}
        return null;
    }

    private Long longOrNull(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v instanceof Number n) return n.longValue();
        if (v != null) try { return Long.parseLong(String.valueOf(v)); } catch (Exception ignored) {}
        return null;
    }

    private long longVal(Map<String, Object> body, String key, long defaultVal) {
        Long v = longOrNull(body, key);
        return v != null ? v : defaultVal;
    }

    private String toJson(Object obj) {
        try { return mapper.writeValueAsString(obj); }
        catch (JsonProcessingException e) { return "{}"; }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        if (json == null) return Map.of();
        try { return mapper.readValue(json, Map.class); }
        catch (Exception e) { return Map.of(); }
    }

    private Object[] buildParams(String status, String outputJson, String errorJson,
                                  Long startedAt, Long finishedAt, String runId) {
        List<Object> params = new ArrayList<>();
        params.add(status);
        if (outputJson != null) params.add(outputJson);
        if (errorJson != null) params.add(errorJson);
        params.add(startedAt);
        params.add(finishedAt);
        params.add(runId);
        return params.toArray();
    }

    private Map<String, Object> mapRun(Map<String, Object> row) {
        return runToMap(
            str(row, "id", ""),
            strOrNull(row, "workflow_id"),
            intOrNull(row, "workflow_version"),
            strOrNull(row, "session_id"),
            str(row, "status", ""),
            str(row, "inputs_json", "{}"),
            strOrNull(row, "output_json"),
            strOrNull(row, "error_json"),
            longVal(row, "created_at", 0L),
            longOrNull(row, "started_at"),
            longOrNull(row, "finished_at")
        );
    }

    private Map<String, Object> runToMap(String id, String workflowId, Integer workflowVersion,
                                          String sessionId, String status, String inputsJson,
                                          String outputJson, String errorJson, long createdAt,
                                          Long startedAt, Long finishedAt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        if (workflowId != null) m.put("workflowId", workflowId);
        if (workflowVersion != null) m.put("workflowVersion", workflowVersion);
        if (sessionId != null) m.put("sessionId", sessionId);
        m.put("status", status);
        m.put("inputs", parseJson(inputsJson));
        if (outputJson != null) m.put("output", parseJson(outputJson));
        if (errorJson != null) m.put("error", parseJson(errorJson));
        m.put("createdAt", createdAt);
        if (startedAt != null) m.put("startedAt", startedAt);
        if (finishedAt != null) m.put("finishedAt", finishedAt);
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapEvent(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", str(row, "id", ""));
        m.put("runId", str(row, "run_id", ""));
        m.put("sequence", ((Number) row.get("sequence")).intValue());
        m.put("type", str(row, "type", ""));
        String actionJson = strOrNull(row, "action_json");
        if (actionJson != null) m.put("action", parseJson(actionJson));
        String payloadJson = strOrNull(row, "payload_json");
        if (payloadJson != null) m.put("payload", parseJson(payloadJson));
        m.put("createdAt", longVal(row, "created_at", 0L));
        return m;
    }
}
