package controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import service.KnowledgeGraphService;
import service.KnowledgeGraphEditingService;
import service.SessionService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/desktop/knowledge-graph")
public class KnowledgeGraphController {

    private static final String USER_ID = "desktop-user";

    private final KnowledgeGraphService graph;
    private final SessionService sessions;
    private final KnowledgeGraphEditingService editing;

    public KnowledgeGraphController(KnowledgeGraphService graph, SessionService sessions,
                                    KnowledgeGraphEditingService editing) {
        this.graph = graph;
        this.sessions = sessions;
        this.editing = editing;
    }

    @GetMapping
    public Map<String, Object> graph(
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "140") int limit) {
        Map<String, Object> data = new LinkedHashMap<>(graph.getGraph(USER_ID, query, limit));
        data.put("status", "ok");
        return data;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return Map.of("status", "ok", "stats", graph.stats(USER_ID));
    }

    @GetMapping("/entities")
    public Map<String, Object> entities() {
        return Map.of("status", "ok", "entities", editing.entities(USER_ID));
    }

    @PostMapping("/entities")
    public Map<String, Object> createEntity(@RequestBody KnowledgeGraphEditingService.EntityInput input) {
        return editing.saveEntity(USER_ID, null, input);
    }

    @PutMapping("/entities/{entityId}")
    public Map<String, Object> updateEntity(@PathVariable String entityId,
            @RequestBody KnowledgeGraphEditingService.EntityInput input) {
        return editing.saveEntity(USER_ID, entityId, input);
    }

    @PostMapping("/relations")
    public Map<String, Object> createRelation(@RequestBody KnowledgeGraphEditingService.RelationInput input) {
        return editing.saveRelation(USER_ID, null, input);
    }

    @PutMapping("/relations/{relationId}")
    public Map<String, Object> updateRelation(@PathVariable String relationId,
            @RequestBody KnowledgeGraphEditingService.RelationInput input) {
        return editing.saveRelation(USER_ID, relationId, input);
    }

    @DeleteMapping("/relations/{relationId}")
    public Map<String, Object> deleteRelation(@PathVariable String relationId) {
        return Map.of("status", "ok", "deleted", editing.deleteRelation(USER_ID, relationId));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> editingError(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("status", "error",
            "message", error.getReason() == null ? "图谱操作失败" : error.getReason()));
    }

    @GetMapping("/entities/{entityId}/evidence")
    public Map<String, Object> evidence(
            @PathVariable String entityId,
            @RequestParam(defaultValue = "20") int limit) {
        List<Map<String, Object>> rows = graph.evidence(USER_ID, entityId, limit);
        return Map.of("status", "ok", "evidence", rows, "count", rows.size());
    }

    @DeleteMapping("/entities/{entityId}")
    public Map<String, Object> delete(@PathVariable String entityId) {
        return Map.of("status", "ok", "deleted", graph.deleteEntity(USER_ID, entityId));
    }

    /** Explicitly rebuilds graph candidates from persisted session history. */
    @PostMapping("/rebuild")
    public Map<String, Object> rebuild(@RequestParam(defaultValue = "20") int sessionLimit) {
        int scheduled = 0;
        List<Map<String, Object>> sessionList = sessions.listSessions(USER_ID);
        for (Map<String, Object> session : sessionList.stream().limit(Math.max(1, Math.min(sessionLimit, 100))).toList()) {
            String sessionId = String.valueOf(session.getOrDefault("id", ""));
            String pendingUser = null;
            for (Map<String, Object> message : sessions.loadMessages(USER_ID, sessionId, 200)) {
                String sender = String.valueOf(message.getOrDefault("sender", message.getOrDefault("role", "")));
                String text = String.valueOf(message.getOrDefault("text", message.getOrDefault("content", "")));
                if ("user".equalsIgnoreCase(sender)) {
                    pendingUser = text;
                } else if (("agent".equalsIgnoreCase(sender) || "assistant".equalsIgnoreCase(sender))
                        && pendingUser != null && !pendingUser.isBlank()) {
                    if (graph.onCompletedTurn(USER_ID, sessionId, pendingUser, text)) scheduled++;
                    pendingUser = null;
                }
            }
        }
        return Map.of("status", "ok", "scheduled", scheduled);
    }
}
