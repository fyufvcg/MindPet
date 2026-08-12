package controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import service.SessionService;

import java.util.*;

/**
 * 桌面端会话管理 API — 替代前端 mock SQLite。
 * Redis 持久化，不设 TTL，手动删除才消失。
 */
@RestController
@RequestMapping("/api/desktop/sessions")
public class SessionController {

    private final SessionService sessionService;

    @Autowired
    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /** 获取用户的所有会话 */
    @GetMapping
    public Map<String, Object> listSessions(
            @RequestParam(defaultValue = "desktop-user") String userId) {
        List<Map<String, Object>> sessions = sessionService.listSessions(userId);
        return Map.of("status", "ok", "sessions", sessions);
    }

    /** 创建/更新会话 */
    @PostMapping
    public Map<String, Object> upsertSession(
            @RequestParam(defaultValue = "desktop-user") String userId,
            @RequestBody Map<String, Object> body) {
        String sessionId = String.valueOf(body.getOrDefault("id", ""));
        if (sessionId.isBlank()) {
            return Map.of("status", "error", "message", "session id 不能为空");
        }
        Map<String, Object> meta = sessionService.upsertSession(userId, sessionId, body);
        if (meta == null) {
            return Map.of("status", "error", "message", "session 创建失败，请重试");
        }
        return Map.of("status", "ok", "session", meta);
    }

    /** 删除会话 */
    @DeleteMapping("/{sessionId}")
    public Map<String, Object> deleteSession(
            @RequestParam(defaultValue = "desktop-user") String userId,
            @PathVariable String sessionId) {
        sessionService.deleteSession(userId, sessionId);
        return Map.of("status", "ok");
    }

    /** 获取会话消息 */
    @GetMapping("/{sessionId}/messages")
    public Map<String, Object> getMessages(
            @RequestParam(defaultValue = "desktop-user") String userId,
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "50") int limit) {
        List<Map<String, Object>> msgs = sessionService.loadMessages(userId, sessionId, limit);
        return Map.of("status", "ok", "messages", msgs);
    }

    /** 追加消息 */
    @PostMapping("/{sessionId}/messages")
    public Map<String, Object> appendMessage(
            @RequestParam(defaultValue = "desktop-user") String userId,
            @PathVariable String sessionId,
            @RequestBody Map<String, Object> msg) {
        sessionService.appendMessage(userId, sessionId, msg);
        return Map.of("status", "ok");
    }

    /** 将已纳入会话摘要的现有消息标记为已总结。 */
    @PostMapping("/{sessionId}/messages/summarized")
    public Map<String, Object> markMessagesSummarized(
            @RequestParam(defaultValue = "desktop-user") String userId,
            @PathVariable String sessionId,
            @RequestBody Map<String, Object> body) {
        Object rawMessages = body.get("messages");
        if (!(rawMessages instanceof List<?> list)) {
            return Map.of("status", "error", "message", "messages 必须是数组");
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> raw) {
                Map<String, Object> message = new LinkedHashMap<>();
                raw.forEach((key, value) -> message.put(String.valueOf(key), value));
                messages.add(message);
            }
        }
        int updated = sessionService.markMessagesSummarized(userId, sessionId, messages);
        return Map.of("status", "ok", "updated", updated);
    }
}
