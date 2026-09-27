package service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import util.Logger;

import java.sql.Timestamp;
import java.sql.Date;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class SqliteMemoryService {

    private static final int PRUNE_THRESHOLD = 500; // 超过此数量时触发清理
    private static final double RETENTION_MIN = 0.1; // 保留率低于此值的记忆视为"已遗忘"

    private final JdbcTemplate jdbc;
    private final EmbeddingService embedService;
    private final VectorSearchService vectorSearch;
    private final Logger logger;

    public SqliteMemoryService(JdbcTemplate jdbc, EmbeddingService embedService,
                                 VectorSearchService vectorSearch, Logger logger) {
        this.jdbc = jdbc;
        this.embedService = embedService;
        this.vectorSearch = vectorSearch;
        this.logger = logger;
        try {
            jdbc.queryForObject("SELECT COUNT(*) FROM long_term_memory", Integer.class);
            logger.log("INFO", "SQLite 长期记忆服务已连接");
        } catch (Exception e) {
            logger.log("ERROR", "SQLite 数据库连接失败: " + e.getMessage());
        }
    }

    /**
     * 存入一条长期记忆，带重要性、层级、情感标签。
     */
    public void append(String userId, String content, String role,
                       double importance, String emotion) {
        appendOne(userId, tool.ToolUserContext.getSessionId(), content, role,
            importance, 1.0, emotion, Instant.now());
    }

    /** Stores the user's evidence after asynchronous evaluation. */
    public void appendTurn(String userId, String sessionId, String userContent,
                           double importance, double confidence, String emotion) {
        appendTurn(userId, sessionId, userContent, importance, confidence, emotion, Instant.now());
    }

    public void appendTurn(String userId, String sessionId, String userContent,
                            double importance, double confidence, String emotion,
                            Instant occurredAt) {
        appendOne(userId, sessionId, userContent, "user", importance, confidence, emotion, occurredAt);
    }

    private void appendOne(String userId, String sessionId, String content, String role,
                           double importance, double confidence, String emotion,
                           Instant occurredAt) {
        if (userId == null || userId.isBlank() || content == null || content.isBlank()) return;
        try {
            float[] vec = embedService.embed(content);
            if (vec == null) {
                logger.log("ERROR", "Embedding返回空: " + content.substring(0, Math.min(20, content.length())));
                return;
            }
            double safeImportance = clamp(importance);
            double safeConfidence = clamp(confidence);
            int layer = MemoryLayer.fromImportance(safeImportance).getLevel();
            TemporalMemory.Resolved temporal = TemporalMemory.resolve(content, occurredAt, ZoneId.systemDefault());
            Date eventDate = temporal.eventDate() == null ? null : Date.valueOf(temporal.eventDate());
            Timestamp eventAt = temporal.eventAt() == null ? null : Timestamp.valueOf(temporal.eventAt());
            jdbc.update(
                "INSERT INTO long_term_memory (user_id,session_id,content,role,embedding,importance,confidence,layer,emotion,event_date,event_at,event_timezone,event_precision,access_count,last_accessed) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,1,CURRENT_TIMESTAMP)",
                userId, sessionId, content, role, VectorSearchService.encode(vec), safeImportance,
                safeConfidence, layer, emotion, eventDate, eventAt, temporal.timezone(), temporal.precision());
            // 超过阈值时触发自动清理
            try {
                Integer total = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM long_term_memory WHERE user_id = ?", Integer.class, userId);
                if (total != null && total > PRUNE_THRESHOLD) {
                    int removed = prune(userId);
                    if (removed > 0) logger.log("INFO", "自动清理 " + removed + " 条低价值记忆 (user=" + userId + ")");
                }
            } catch (Exception ignored) {}
        } catch (Exception e) {
            logger.log("WARN", "SQLite memory append failed: " + e.getMessage());
        }
    }

    /** Convenience — computes embedding internally. */
    private double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 0.5;
        return Math.max(0.0, Math.min(1.0, value));
    }

    public List<MemoryResult> search(String userId, String query, int topK) {
        return search(userId, query, embedService.embed(query), topK);
    }

    /**
     * 混合检索 with pre-computed embedding（query 用于关键词匹配，vec 复用以省 API 调用）。
     */
    public List<MemoryResult> search(String userId, String query, float[] vec, int topK) {
        if (vec == null) return List.of();
        try {
            // 1. 语义召回（sqlite-vec 或 Java 精确余弦，取更宽的范围供 RRF 融合）
            List<MemoryResult> semanticResults = semanticSearch(userId, vec, 20);

            // 2. 关键词召回（中文子串 + 词匹配）
            List<MemoryResult> keywordResults = keywordSearch(userId, query, 20);

            // 3. RRF 融合
            java.util.Map<String, Double> rrfScores = new java.util.LinkedHashMap<>();
            double k = 60;
            for (int i = 0; i < semanticResults.size(); i++) {
                rrfScores.merge(contentId(semanticResults.get(i)), 1.0 / (k + i + 1), Double::sum);
            }
            for (int i = 0; i < keywordResults.size(); i++) {
                rrfScores.merge(contentId(keywordResults.get(i)), 1.0 / (k + i + 1), Double::sum);
            }

            // 4. Reranking：RRF 0.5 + 时间衰减 0.2 + 情感 0.15 + 重要性 0.1 + 层级 0.05
            var allResults = new java.util.ArrayList<>(semanticResults);
            allResults.addAll(keywordResults);
            var byContent = allResults.stream()
                .collect(java.util.stream.Collectors.toMap(
                    r -> contentId(r), r -> r, (a, b) -> a));

            List<MemoryResult> ranked = byContent.values().stream()
                .sorted((a, b) -> {
                    double sa = rerankScore(a, rrfScores.getOrDefault(contentId(a), 0.0));
                    double sb = rerankScore(b, rrfScores.getOrDefault(contentId(b), 0.0));
                    return Double.compare(sb, sa);
                })
                .limit(topK)
                .toList();
            touchAccessed(userId, ranked);
            return ranked;
        } catch (Exception e) {
            logger.log("WARN", "SQLite vector search failed: " + e.getMessage());
            return List.of();
        }
    }

    private List<MemoryResult> semanticSearch(String userId, String query, int limit) {
        return semanticSearch(userId, embedService.embed(query), limit);
    }

    private List<MemoryResult> semanticSearch(String userId, float[] vec, int limit) {
        if (vec == null) return List.of();
        try {
            List<MemoryResult> results = new ArrayList<>();
            for (VectorSearchService.VectorMatch match : vectorSearch.search("long_term_memory", userId, vec, Math.max(limit * 4, limit))) {
                List<MemoryResult> rows = jdbc.query(
                    "SELECT id,content,role,created_at,event_date,event_at,event_timezone,event_precision,importance,COALESCE(confidence,1.0) confidence,layer,emotion,last_accessed "
                        + "FROM long_term_memory WHERE user_id=? AND id=?",
                    (rs, rn) -> new MemoryResult(rs.getString("id"), rs.getString("content"), rs.getString("role"),
                        rs.getTimestamp("created_at"), rs.getDate("event_date"), rs.getTimestamp("event_at"),
                        rs.getString("event_timezone"), rs.getString("event_precision"), match.distance(),
                        rs.getDouble("importance"), rs.getDouble("confidence"), rs.getString("emotion"), rs.getInt("layer"),
                        retention(rs.getTimestamp("last_accessed"), rs.getTimestamp("created_at"), rs.getDouble("importance"), rs.getInt("layer"))),
                    userId, match.id());
                if (!rows.isEmpty() && rows.get(0).retentionRate() > RETENTION_MIN) results.add(rows.get(0));
                if (results.size() >= limit) break;
            }
            return results;
        } catch (Exception e) { return List.of(); }
    }

    /** 关键词匹配：中文子串 + 2-4字分词匹配 */
    private List<MemoryResult> keywordSearch(String userId, String query, int limit) {
        try {
            return jdbc.query(
                "SELECT id, content, role, created_at, event_date, event_at, event_timezone, event_precision, importance, COALESCE(confidence,1.0) AS confidence, layer, emotion, 0.5 AS distance "
                + "FROM long_term_memory WHERE user_id = ? "
                + "ORDER BY importance DESC LIMIT 100",
                ps -> ps.setString(1, userId),
                (rs, rn) -> new MemoryResult(rs.getString("id"), rs.getString("content"), rs.getString("role"),
                    rs.getTimestamp("created_at"), rs.getDate("event_date"), rs.getTimestamp("event_at"),
                    rs.getString("event_timezone"), rs.getString("event_precision"), 0.5, rs.getDouble("importance"), rs.getDouble("confidence"), rs.getString("emotion"),
                    rs.getInt("layer"), 1.0)
            ).stream().filter(r -> retention(r.createdAt(), r.createdAt(), r.importance(), r.layer()) > RETENTION_MIN)
              .filter(r -> matchScore(r.content(), query) > 0)
              .sorted((a, b) -> Double.compare(matchScore(b.content(), query), matchScore(a.content(), query)))
              .limit(limit).toList();
        } catch (Exception e) { return List.of(); }
    }

    /** 简单关键词匹配分 */
    private double matchScore(String content, String query) {
        String c = content.toLowerCase();
        String q = query.toLowerCase();
        if (c.contains(q)) return 0.8;
        // 2-4字子串匹配
        int hits = 0;
        for (int len = 2; len <= 4; len++) {
            for (int i = 0; i <= q.length() - len; i++) {
                if (c.contains(q.substring(i, i + len))) hits++;
            }
        }
        return Math.min(0.6, hits * 0.15);
    }

    private double rerankScore(MemoryResult r, double rrfScore) {
        long elapsed = System.currentTimeMillis() - (r.createdAt() != null ? r.createdAt().getTime() : 0);
        double hours = elapsed / 3600000.0;
        double strength = r.importance() >= 0.6 ? 5.0 : 1.0;
        double timeDecay = Math.exp(-hours / (strength * 24 + 1));
        return rrfScore * 0.5 + timeDecay * 0.2 + r.importance() * 0.2
            + r.confidence() * 0.05 + (r.importance() >= 0.6 ? 0.05 : 0);
    }

    private void touchAccessed(String userId, List<MemoryResult> results) {
        for (MemoryResult result : results) {
            if (result.id() == null || result.id().isBlank()) continue;
            try {
                jdbc.update("UPDATE long_term_memory SET access_count=COALESCE(access_count,0)+1, "
                    + "last_accessed=CURRENT_TIMESTAMP WHERE user_id=? AND id=?", userId, result.id());
            } catch (Exception e) {
                logger.log("DEBUG", "SQLite memory access refresh failed: " + e.getMessage());
            }
        }
    }

    private static String contentId(MemoryResult r) {
        return r.id() != null ? r.id() : r.content();
    }

    /**
     * 清理保留率低于阈值的常规记忆（已遗忘的）。
     */
    public int prune(String userId) {
        try {
            List<String> ids = jdbc.query("SELECT id,importance,layer,last_accessed,created_at FROM long_term_memory WHERE user_id=? AND access_count<3",
                (rs, row) -> retention(rs.getTimestamp("last_accessed"), rs.getTimestamp("created_at"), rs.getDouble("importance"), rs.getInt("layer")) < RETENTION_MIN
                    ? rs.getString("id") : null, userId).stream().filter(java.util.Objects::nonNull).toList();
            int removed = 0;
            for (String id : ids) removed += jdbc.update("DELETE FROM long_term_memory WHERE user_id=? AND id=?", userId, id);
            return removed;
        } catch (Exception e) {
            logger.log("WARN", "SQLite memory prune failed: " + e.getMessage());
            return 0;
        }
    }

    // ==================== CRUD for desktop memory management ====================

    public List<MemoryResult> getRecent(String userId, int limit) {
        try {
            return jdbc.query(
                "SELECT id, content, role, created_at, event_date, event_at, event_timezone, event_precision, 0.0 as distance, "
                + "COALESCE(importance, 0.5) as importance, COALESCE(confidence, 1.0) as confidence, "
                + "COALESCE(emotion, 'neutral') as emotion "
                + "FROM long_term_memory WHERE user_id = ? "
                + "ORDER BY created_at DESC LIMIT ?",
                (rs, rowNum) -> new MemoryResult(
                    rs.getString("id"), rs.getString("content"), rs.getString("role"),
                    rs.getTimestamp("created_at"), rs.getDate("event_date"), rs.getTimestamp("event_at"),
                    rs.getString("event_timezone"), rs.getString("event_precision"), 0.0,
                    rs.getDouble("importance"), rs.getDouble("confidence"), rs.getString("emotion"), 1, 1.0
                ),
                userId, limit
            );
        } catch (Exception e) {
            return List.of();
        }
    }

    public int delete(String memoryId) {
        try {
            return jdbc.update("DELETE FROM long_term_memory WHERE id = ?", memoryId);
        } catch (Exception e) {
            return 0;
        }
    }

    public int count(String userId) {
        try {
            Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM long_term_memory WHERE user_id = ?",
                Integer.class, userId);
            return c != null ? c : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private double retention(Timestamp lastAccessed, Timestamp createdAt, double importance, int layer) {
        Timestamp anchor = lastAccessed != null ? lastAccessed : createdAt;
        double hours = anchor == null ? 0 : Math.max(0, System.currentTimeMillis() - anchor.getTime()) / 3_600_000.0;
        double strength = layer == 2 ? 5.0 : 1.0;
        return importance * Math.exp(-hours / (strength * 24 + 1));
    }

    public record MemoryResult(
        String id, String content, String role,
        java.sql.Timestamp createdAt, java.sql.Date eventDate, java.sql.Timestamp eventAt,
        String eventTimezone, String eventPrecision, double distance,
        double importance, double confidence, String emotion,
        int layer, double retentionRate
    ) {
        // legacy constructor for search results
        public MemoryResult(String content, String role,
                            java.sql.Timestamp createdAt, double distance,
                            double importance, String emotion) {
            this(null, content, role, createdAt, null, null, null, null,
                distance, importance, 1.0, emotion, 1, 1.0);
        }

        private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

        public String toPromptLine() {
            TemporalMemory.Resolved legacyTemporal = eventDate == null && eventAt == null && createdAt != null
                ? TemporalMemory.resolve(content, createdAt.toInstant(), ZoneId.systemDefault()) : null;
            java.time.LocalDate displayDate = eventDate != null ? eventDate.toLocalDate()
                : legacyTemporal == null ? null : legacyTemporal.eventDate();
            LocalDateTime displayAt = eventAt != null ? eventAt.toLocalDateTime()
                : legacyTemporal == null ? null : legacyTemporal.eventAt();
            String displayTimezone = eventTimezone != null ? eventTimezone
                : legacyTemporal == null ? null : legacyTemporal.timezone();
            if (displayDate != null || displayAt != null) {
                ZoneId zone;
                try {
                    zone = displayTimezone == null || displayTimezone.isBlank()
                        ? ZoneId.systemDefault() : ZoneId.of(displayTimezone);
                } catch (RuntimeException ignored) {
                    zone = ZoneId.systemDefault();
                }
                LocalDateTime target = displayAt;
                String eventText = target == null ? displayDate.toString()
                    : target.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                String relative = TemporalMemory.relativeLabel(
                    target == null ? displayDate : target.toLocalDate(), zone);
                String prefix = "user".equals(role) ? "用户" : "MindPet";
                String impTag = importance >= 0.6 ? "*" : "";
                return impTag + "[事件时间 " + eventText + "，" + relative + "] " + prefix + ": " + content;
            }
            String time = createdAt != null
                ? createdAt.toLocalDateTime().format(FMT) : "未知";
            String prefix = "user".equals(role) ? "用户" : "MindPet";
            String impTag = importance >= 0.6 ? "★" : "";
            return impTag + "[" + time + "] " + prefix + ": " + content;
        }
    }
}
