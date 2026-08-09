package service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import util.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** User-wide completed-turn history used only by the memory curator. */
@Service
public class CuratorTurnStore {

    private static final String COUNT_KEY = "curator:turn_count:";
    private static final String TURNS_KEY = "curator:turns:";
    private static final String DEDUP_KEY = "curator:turn_seen:";
    private static final String CHECKPOINT_KEY = "curator:checkpoint:";
    private static final String LOCK_KEY = "curator:lock:";
    private static final String WORKING_MEMORY_KEY = "working_memory:";
    private static final String RUNS_KEY = "curator:runs:";
    private static final int MAX_STORED_TURNS = 500;
    private static final Duration TURN_TTL = Duration.ofDays(30);

    private static final DefaultRedisScript<Long> APPEND_SCRIPT = new DefaultRedisScript<>("""
        if redis.call('SETNX', KEYS[3], '1') == 0 then
          return 0
        end
        redis.call('EXPIRE', KEYS[3], ARGV[3])
        local seq = redis.call('INCR', KEYS[1])
        redis.call('ZADD', KEYS[2], seq, ARGV[1])
        local size = redis.call('ZCARD', KEYS[2])
        local maxSize = tonumber(ARGV[2])
        if size > maxSize then
          redis.call('ZREMRANGEBYRANK', KEYS[2], 0, size - maxSize - 1)
        end
        redis.call('EXPIRE', KEYS[2], ARGV[3])
        return seq
        """, Long.class);

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) == ARGV[1] then
          return redis.call('DEL', KEYS[1])
        end
        return 0
        """, Long.class);

    public record CompletedTurn(String turnId, String sessionId, String source,
                                String userMessage, String assistantReply, String completedAt) {}

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Logger logger;

    public CuratorTurnStore(StringRedisTemplate redis, ObjectMapper mapper, Logger logger) {
        this.redis = redis;
        this.mapper = mapper;
        this.logger = logger;
    }

    public long append(String userId, String sessionId, String source,
                       String userMessage, String assistantReply) {
        try {
            String turnId = UUID.randomUUID().toString();
            CompletedTurn turn = new CompletedTurn(turnId, sessionId, source,
                userMessage, assistantReply, Instant.now().toString());
            Long sequence = redis.execute(APPEND_SCRIPT,
                List.of(COUNT_KEY + userId, TURNS_KEY + userId, DEDUP_KEY + userId + ":" + turnId),
                mapper.writeValueAsString(turn), String.valueOf(MAX_STORED_TURNS),
                String.valueOf(TURN_TTL.toSeconds()));
            return sequence == null ? 0 : sequence;
        } catch (Exception e) {
            logger.log("WARN", "记忆馆长回合写入失败: " + e.getMessage());
            return 0;
        }
    }

    public long count(String userId) {
        return readLong(COUNT_KEY + userId);
    }

    public long checkpoint(String userId) {
        return readLong(CHECKPOINT_KEY + userId);
    }

    public void saveCheckpoint(String userId, long sequence) {
        redis.opsForValue().set(CHECKPOINT_KEY + userId, String.valueOf(sequence));
    }

    public List<CompletedTurn> recentAt(String userId, long targetSequence, int limit) {
        Set<String> raw = redis.opsForZSet().reverseRangeByScore(
            TURNS_KEY + userId, 0, targetSequence, 0, limit);
        if (raw == null || raw.isEmpty()) return List.of();

        List<CompletedTurn> turns = new ArrayList<>(raw.size());
        for (String json : raw) {
            try {
                turns.add(mapper.readValue(json, CompletedTurn.class));
            } catch (Exception e) {
                logger.log("WARN", "跳过损坏的馆长回合: " + e.getMessage());
            }
        }
        Collections.reverse(turns);
        return turns;
    }

    public String tryLock(String userId) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(
            LOCK_KEY + userId, token, Duration.ofMinutes(10));
        return Boolean.TRUE.equals(acquired) ? token : null;
    }

    public void unlock(String userId, String token) {
        try {
            redis.execute(UNLOCK_SCRIPT, List.of(LOCK_KEY + userId), token);
        } catch (Exception e) {
            logger.log("WARN", "记忆馆长锁释放失败: " + e.getMessage());
        }
    }

    public void saveWorkingMemory(String userId, Object value) throws Exception {
        redis.opsForValue().set(WORKING_MEMORY_KEY + userId,
            mapper.writeValueAsString(value), Duration.ofDays(30));
    }

    public String getWorkingMemory(String userId) {
        return redis.opsForValue().get(WORKING_MEMORY_KEY + userId);
    }

    public void recordRun(String userId, Object value) {
        try {
            String key = RUNS_KEY + userId;
            redis.opsForList().rightPush(key, mapper.writeValueAsString(value));
            redis.opsForList().trim(key, -100, -1);
            redis.expire(key, Duration.ofDays(30));
        } catch (Exception e) {
            logger.log("WARN", "记忆馆长运行记录写入失败: " + e.getMessage());
        }
    }

    public List<Map<String, Object>> recentRuns(String userId, int limit) {
        List<String> raw = redis.opsForList().range(RUNS_KEY + userId, -limit, -1);
        if (raw == null || raw.isEmpty()) return List.of();
        List<Map<String, Object>> runs = new ArrayList<>();
        for (String json : raw) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> run = mapper.readValue(json, Map.class);
                runs.add(run);
            } catch (Exception e) {
                logger.log("WARN", "跳过损坏的馆长运行记录: " + e.getMessage());
            }
        }
        return runs;
    }

    private long readLong(String key) {
        try {
            String value = redis.opsForValue().get(key);
            return value == null ? 0 : Long.parseLong(value);
        } catch (Exception e) {
            return 0;
        }
    }
}
