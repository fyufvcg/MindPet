package service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tool.ToolUserContext;
import util.Logger;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 会话存储 — Redis 持久化，不设 TTL，手动删才消失。
 * 按 userId:sessionId 隔离。
 */
@Service
public class SessionService {

    private static final String SESSION_LIST_KEY = "session:list:";
    private static final String SESSION_META_KEY = "session:meta:";
    private static final String SESSION_MSGS_KEY = "session:msgs:";
    private static final int MAX_MSGS = 200;

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Logger logger;

    @Autowired
    public SessionService(StringRedisTemplate redis, Logger logger) {
        this.redis = redis;
        this.logger = logger;
    }

    /** 获取用户的所有会话列表（按更新时间倒序） */
    public List<Map<String, Object>> listSessions(String userId) {
        String key = SESSION_LIST_KEY + userId;
        Set<String> ids = redis.opsForZSet().reverseRange(key, 0, 99);
        if (ids == null || ids.isEmpty()) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (String sid : ids) {
            Map<String, Object> meta = getMeta(userId, sid);
            if (meta != null) result.add(meta);
        }
        return result;
    }

    /** 创建或更新会话 */
    public Map<String, Object> upsertSession(String userId, String sessionId, Map<String, Object> updates) {
        String listKey = SESSION_LIST_KEY + userId;
        String metaKey = metaKey(userId, sessionId);

        Map<String, String> meta = new LinkedHashMap<>();
        String name = String.valueOf(updates.getOrDefault("name", "")).trim();
        if (redis.opsForHash().hasKey(metaKey, "id")) {
            // 已有会话，只更新时间和名字
            if (!name.isBlank()) meta.put("name", name);
        } else {
            meta.put("id", sessionId);
            meta.put("name", name);
            meta.put("created_at", java.time.LocalDateTime.now().toString());
        }
        if (updates.containsKey("contextSummary") || updates.containsKey("context_summary")) {
            Object summary = updates.containsKey("contextSummary")
                ? updates.get("contextSummary") : updates.get("context_summary");
            meta.put("context_summary", summary == null ? "" : String.valueOf(summary));
        }
        if (updates.containsKey("pinned")) {
            meta.put("pinned", String.valueOf(asBoolean(updates.get("pinned"))));
        } else if (!redis.opsForHash().hasKey(metaKey, "id")) {
            meta.put("pinned", "false");
        }
        meta.put("updated_at", java.time.LocalDateTime.now().toString());
        redis.opsForHash().putAll(metaKey, meta);
        redis.opsForZSet().add(listKey, sessionId, System.currentTimeMillis() / 1000.0);
        Map<String, Object> result = getMeta(userId, sessionId);
        return result != null ? result : new LinkedHashMap<>(meta);
    }

    /** 删除会话及其消息 */
    public void deleteSession(String userId, String sessionId) {
        redis.opsForZSet().remove(SESSION_LIST_KEY + userId, sessionId);
        redis.delete(metaKey(userId, sessionId));
        redis.delete(msgsKey(userId, sessionId));
    }

    /** 追加消息到会话 */
    public synchronized void appendMessage(String userId, String sessionId, Map<String, Object> msg) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>(msg);
            String messageId = String.valueOf(payload.getOrDefault("id", "")).trim();
            if (messageId.isBlank()) {
                messageId = UUID.randomUUID().toString();
                payload.put("id", messageId);
            }

            String json = mapper.writeValueAsString(payload);
            String key = msgsKey(userId, sessionId);
            List<String> existing = redis.opsForList().range(key, 0, -1);
            int existingIndex = findMessageIndex(existing, messageId);
            if (existingIndex >= 0) {
                redis.opsForList().set(key, existingIndex, json);
            } else {
                redis.opsForList().rightPush(key, json);
                redis.opsForList().trim(key, -MAX_MSGS, -1);
            }
            // 更新会话时间
            redis.opsForZSet().add(SESSION_LIST_KEY + userId, sessionId, System.currentTimeMillis() / 1000.0);
        } catch (Exception e) {
            logger.log("ERROR", "追加消息失败: " + e.getMessage());
        }
    }

    /** 读取会话消息 */
    public List<Map<String, Object>> loadMessages(String userId, String sessionId, int limit) {
        String key = msgsKey(userId, sessionId);
        // Deduplicate before applying the limit. Old clients could append a full
        // history copy on every reload, so limiting the raw Redis list first can
        // hide older unique messages behind duplicate entries.
        List<String> raw = redis.opsForList().range(key, 0, -1);
        if (raw == null || raw.isEmpty()) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, Integer> indexById = new HashMap<>();
        Map<String, Integer> indexByFingerprint = new HashMap<>();
        for (String s : raw) {
            try {
                Map<String, Object> message = mapper.readValue(s, new TypeReference<Map<String, Object>>() {});
                String fingerprint = messageFingerprint(message);
                String messageId = String.valueOf(message.getOrDefault("id", "")).trim();
                if (messageId.isBlank()) {
                    messageId = "redis-legacy-" + UUID.nameUUIDFromBytes(
                        (sessionId + "|" + fingerprint).getBytes(StandardCharsets.UTF_8));
                    message.put("id", messageId);
                }

                Integer existingIndex = indexById.get(messageId);
                if (existingIndex == null) existingIndex = indexByFingerprint.get(fingerprint);
                if (existingIndex == null) {
                    int index = result.size();
                    result.add(message);
                    indexById.put(messageId, index);
                    indexByFingerprint.put(fingerprint, index);
                } else {
                    result.set(existingIndex, message);
                    indexById.put(messageId, existingIndex);
                    indexByFingerprint.put(fingerprint, existingIndex);
                }
            } catch (Exception ignored) {}
        }
        if (limit > 0 && result.size() > limit) {
            return new ArrayList<>(result.subList(result.size() - limit, result.size()));
        }
        return result;
    }

    /** Load messages from every session for cross-session archive statistics. */
    public List<Map<String, Object>> loadAllMessages(String userId, int limit) {
        if (userId == null || userId.isBlank()) return List.of();

        List<Map<String, Object>> all = new ArrayList<>();
        for (Map<String, Object> session : listSessions(userId)) {
            String sessionId = String.valueOf(session.getOrDefault("id", ""));
            if (!sessionId.isBlank()) {
                all.addAll(loadMessages(userId, sessionId, 0));
            }
        }
        all.sort((a, b) -> String.valueOf(b.getOrDefault("time", ""))
            .compareTo(String.valueOf(a.getOrDefault("time", ""))));
        if (limit > 0 && all.size() > limit) {
            return new ArrayList<>(all.subList(0, limit));
        }
        return all;
    }

    public synchronized int markMessagesSummarized(String userId, String sessionId,
                                                    List<Map<String, Object>> references) {
        if (references == null || references.isEmpty()) return 0;
        Set<String> ids = new HashSet<>();
        Set<String> contentKeys = new HashSet<>();
        for (Map<String, Object> reference : references) {
            String id = String.valueOf(reference.getOrDefault("id", "")).trim();
            if (!id.isBlank()) ids.add(id);
            contentKeys.add(messageContentKey(reference));
        }

        String key = msgsKey(userId, sessionId);
        List<String> raw = redis.opsForList().range(key, 0, -1);
        if (raw == null || raw.isEmpty()) return 0;
        int updated = 0;
        for (int i = 0; i < raw.size(); i++) {
            try {
                Map<String, Object> message = mapper.readValue(
                    raw.get(i), new TypeReference<Map<String, Object>>() {});
                String id = String.valueOf(message.getOrDefault("id", "")).trim();
                if (!ids.contains(id) && !contentKeys.contains(messageContentKey(message))) continue;
                if (Boolean.TRUE.equals(message.get("isSummarized"))) continue;
                message.put("isSummarized", true);
                redis.opsForList().set(key, i, mapper.writeValueAsString(message));
                updated++;
            } catch (Exception ignored) {}
        }
        return updated;
    }

    private int findMessageIndex(List<String> raw, String messageId) {
        if (raw == null || raw.isEmpty()) return -1;
        for (int i = 0; i < raw.size(); i++) {
            try {
                Map<String, Object> existing = mapper.readValue(
                    raw.get(i), new TypeReference<Map<String, Object>>() {});
                if (messageId.equals(String.valueOf(existing.getOrDefault("id", "")).trim())) return i;
            } catch (Exception ignored) {}
        }
        return -1;
    }

    private String messageFingerprint(Map<String, Object> message) {
        return String.valueOf(message.getOrDefault("sender", "")) + "|"
            + String.valueOf(message.getOrDefault("text", "")) + "|"
            + String.valueOf(message.getOrDefault("time", ""));
    }

    private String messageContentKey(Map<String, Object> message) {
        return String.valueOf(message.getOrDefault("sender", "")) + "|"
            + String.valueOf(message.getOrDefault("text", ""));
    }

    private boolean asBoolean(Object value) {
        if (value instanceof Boolean booleanValue) return booleanValue;
        String text = String.valueOf(value).trim();
        return "true".equalsIgnoreCase(text) || "1".equals(text);
    }

    private Map<String, Object> getMeta(String userId, String sessionId) {
        Map<Object, Object> raw = redis.opsForHash().entries(metaKey(userId, sessionId));
        if (raw.isEmpty()) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((k, v) -> result.put(String.valueOf(k), v));
        return result;
    }

    private String metaKey(String userId, String sid) { return SESSION_META_KEY + userId + ":" + sid; }
    private String msgsKey(String userId, String sid) { return SESSION_MSGS_KEY + userId + ":" + sid; }
}
