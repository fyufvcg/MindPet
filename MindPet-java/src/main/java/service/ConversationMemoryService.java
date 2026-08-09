package service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tool.ToolUserContext;
import util.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ConversationMemoryService {

    private static final String KEY_PREFIX = "chat:history:";
    private static final Duration REDIS_TTL = Duration.ofDays(7);
    private static final int LOCAL_MAX_SIZE = 200;

    private final StringRedisTemplate redisTemplate;
    private final boolean redisEnabled;
    private final Logger logger;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Deque<String>> localStore = new ConcurrentHashMap<>();
    private final AtomicBoolean redisFallbackLogged = new AtomicBoolean(false);

    public ConversationMemoryService(
        ObjectProvider<StringRedisTemplate> redisTemplateProvider,
        @Value("${app.memory.redis.enabled:false}") boolean redisEnabled,
        Logger logger
    ) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.redisEnabled = redisEnabled;
        this.logger = logger;
    }

    public void append(String userId, Map<String, Object> message) {
        if (userId == null || userId.isBlank() || message == null) {
            return;
        }

        String serialized = null;
        try {
            serialized = objectMapper.writeValueAsString(message);
            if (useRedis()) {
                String key = memoryKey(userId);
                redisTemplate.opsForList().rightPush(key, serialized);
                redisTemplate.opsForList().trim(key, -LOCAL_MAX_SIZE, -1);
                redisTemplate.expire(key, REDIS_TTL);
                return;
            }
            appendLocal(userId, serialized);
        } catch (Exception e) {
            logRedisFallbackOnce("写入失败，已回退到本地内存: " + e.getMessage());
            if (serialized != null) {
                appendLocal(userId, serialized);
            }
        }
    }

    public List<Map<String, Object>> loadRecent(String userId, int limit) {
        if (userId == null || userId.isBlank() || limit <= 0) {
            return Collections.emptyList();
        }

        try {
            if (useRedis()) {
                List<String> raw = redisTemplate.opsForList().range(memoryKey(userId), -limit, -1);
                return deserialize(raw);
            }
        } catch (Exception e) {
            logRedisFallbackOnce("读取失败，已回退到本地内存: " + e.getMessage());
        }

        return loadLocal(userId, limit);
    }

    public void clear(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }

        if (useRedis()) {
            try {
                redisTemplate.delete(memoryKey(userId));
                return;
            } catch (Exception e) {
                logRedisFallbackOnce("清理失败，已回退到本地内存: " + e.getMessage());
            }
        }

        localStore.remove(userId);
    }

    /** 跨所有 session 加载对话（用于设置页展示），最多 limit 条 */
    public List<Map<String, Object>> loadRecentAllSessions(String userId, int limit) {
        if (userId == null || userId.isBlank() || limit <= 0) return List.of();
        try {
            if (useRedis()) {
                var keys = redisTemplate.keys(KEY_PREFIX + userId + ":*");
                if (keys == null || keys.isEmpty()) return List.of();
                List<Map<String, Object>> all = new ArrayList<>();
                for (String key : keys) {
                    List<String> raw = redisTemplate.opsForList().range(key, -limit, -1);
                    if (raw != null) all.addAll(deserialize(raw));
                }
                all.sort((a, b) -> {
                    String ta = String.valueOf(a.getOrDefault("time", ""));
                    String tb = String.valueOf(b.getOrDefault("time", ""));
                    return tb.compareTo(ta);
                });
                if (all.size() > limit) all = all.subList(0, limit);
                return all;
            }
        } catch (Exception ignored) {}
        return loadLocal(userId, limit);
    }

    private boolean useRedis() {
        return redisEnabled && redisTemplate != null;
    }

    /** 按 userId:sessionId 隔离短期记忆，不同会话/端互不干扰 */
    private String memoryKey(String userId) {
        String sid = ToolUserContext.getSessionId();
        return KEY_PREFIX + userId + ":" + sid;
    }

    private void appendLocal(String userId, String serialized) {
        Deque<String> queue = localStore.computeIfAbsent(userId, key -> new ConcurrentLinkedDeque<>());
        queue.addLast(serialized);
        trimLocal(queue);
    }

    private List<Map<String, Object>> loadLocal(String userId, int limit) {
        Deque<String> queue = localStore.get(userId);
        if (queue == null || queue.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> snapshot = new ArrayList<>(queue);
        int start = Math.max(0, snapshot.size() - limit);
        return deserialize(snapshot.subList(start, snapshot.size()));
    }

    private void trimLocal(Deque<String> queue) {
        while (queue.size() > LOCAL_MAX_SIZE) {
            queue.pollFirst();
        }
    }

    private List<Map<String, Object>> deserialize(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> result = new ArrayList<>(raw.size());
        for (String item : raw) {
            try {
                result.add(objectMapper.readValue(item, new TypeReference<Map<String, Object>>() {}));
            } catch (Exception e) {
                logger.log("WARN", "跳过一条损坏的记忆记录: " + e.getMessage());
            }
        }
        return result;
    }

    private void logRedisFallbackOnce(String reason) {
        if (redisFallbackLogged.compareAndSet(false, true)) {
            logger.log("WARN", "Redis 短期记忆不可用，已回退到本地内存: " + reason);
        }
    }
}
