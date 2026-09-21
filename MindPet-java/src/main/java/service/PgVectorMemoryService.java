package service;

import model.RetrievalDebugResult;
import model.RetrievalMode;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import util.Logger;

import java.sql.Timestamp;
import java.sql.Date;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class PgVectorMemoryService {

    private static final int PRUNE_THRESHOLD = 500; // 超过此数量时触发清理
    private static final double RETENTION_MIN = 0.1; // 保留率低于此值的记忆视为"已遗忘"
    static final double RRF_MAX = 2.0 / 61.0; // Evaluation-only normalization ceiling for k=60.

    private final JdbcTemplate jdbc;
    private final EmbeddingService embedService;
    private final Logger logger;

    public PgVectorMemoryService(JdbcTemplate jdbc, EmbeddingService embedService, Logger logger) {
        this.jdbc = jdbc;
        this.embedService = embedService;
        this.logger = logger;
        try {
            jdbc.queryForObject("SELECT COUNT(*) FROM long_term_memory", Integer.class);
            // v2 自动迁移：新增重要性/分层/情感/访问统计字段
            try { jdbc.execute("ALTER TABLE long_term_memory ADD COLUMN IF NOT EXISTS importance FLOAT DEFAULT 0.5"); } catch (Exception ignored) {}
            try { jdbc.execute("ALTER TABLE long_term_memory ADD COLUMN IF NOT EXISTS layer INT DEFAULT 3"); } catch (Exception ignored) {}
            try { jdbc.execute("ALTER TABLE long_term_memory ADD COLUMN IF NOT EXISTS emotion VARCHAR(32)"); } catch (Exception ignored) {}
            try { jdbc.execute("ALTER TABLE long_term_memory ADD COLUMN IF NOT EXISTS access_count INT DEFAULT 0"); } catch (Exception ignored) {}
            try { jdbc.execute("ALTER TABLE long_term_memory ADD COLUMN IF NOT EXISTS last_accessed TIMESTAMP DEFAULT NOW()"); } catch (Exception ignored) {}
            try { jdbc.execute("ALTER TABLE long_term_memory ADD COLUMN IF NOT EXISTS session_id VARCHAR(128)"); } catch (Exception ignored) {}
            try { jdbc.execute("ALTER TABLE long_term_memory ADD COLUMN IF NOT EXISTS confidence FLOAT DEFAULT 1.0"); } catch (Exception ignored) {}
            try { jdbc.execute("ALTER TABLE long_term_memory ADD COLUMN IF NOT EXISTS event_date DATE"); } catch (Exception ignored) {}
            try { jdbc.execute("ALTER TABLE long_term_memory ADD COLUMN IF NOT EXISTS event_at TIMESTAMP"); } catch (Exception ignored) {}
            try { jdbc.execute("ALTER TABLE long_term_memory ADD COLUMN IF NOT EXISTS event_timezone VARCHAR(64)"); } catch (Exception ignored) {}
            try { jdbc.execute("ALTER TABLE long_term_memory ADD COLUMN IF NOT EXISTS event_precision VARCHAR(16)"); } catch (Exception ignored) {}
            logger.log("INFO", "PgVector 长期记忆服务已连接 → PostgreSQL:mindpet (v2 分层+遗忘+情感)");
        } catch (Exception e) {
            logger.log("ERROR", "PgVector 数据库连接失败: " + e.getMessage());
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
            String vecStr = EmbeddingService.toPgVectorString(vec);
            double safeImportance = clamp(importance);
            double safeConfidence = clamp(confidence);
            int layer = MemoryLayer.fromImportance(safeImportance).getLevel();
            TemporalMemory.Resolved temporal = TemporalMemory.resolve(content, occurredAt, ZoneId.systemDefault());
            Date eventDate = temporal.eventDate() == null ? null : Date.valueOf(temporal.eventDate());
            Timestamp eventAt = temporal.eventAt() == null ? null : Timestamp.valueOf(temporal.eventAt());
            try {
                jdbc.update(
                    "INSERT INTO long_term_memory (user_id, session_id, content, role, embedding, importance, confidence, layer, emotion, event_date, event_at, event_timezone, event_precision, access_count, last_accessed) "
                    + "VALUES (?,?,?,?,?::vector,?,?,?,?,?,?,?, ?,1,NOW())",
                    userId, sessionId, content, role, vecStr, safeImportance, safeConfidence, layer, emotion,
                    eventDate, eventAt, temporal.timezone(), temporal.precision()
                );
            } catch (Exception ignored) {
                // session_id 列可能还未迁移，回退到无 session_id 的 INSERT
                    try {
                        jdbc.update(
                            "INSERT INTO long_term_memory (user_id, content, role, embedding, importance, confidence, layer, emotion, event_date, event_at, event_timezone, event_precision, access_count, last_accessed) "
                            + "VALUES (?,?,?,?::vector,?,?,?,?,?,?,?, ?,1,NOW())",
                            userId, content, role, vecStr, safeImportance, safeConfidence, layer, emotion,
                            eventDate, eventAt, temporal.timezone(), temporal.precision()
                        );
                    } catch (Exception extendedSchemaFailure) {
                        try {
                            jdbc.update(
                                "INSERT INTO long_term_memory (user_id, content, role, embedding, importance, layer, emotion, access_count, last_accessed) "
                                + "VALUES (?,?,?,?::vector,?,?,?,1,NOW())",
                                userId, content, role, vecStr, safeImportance, layer, emotion
                            );
                        } catch (Exception legacyMetadataFailure) {
                            jdbc.update(
                                "INSERT INTO long_term_memory (user_id, content, role, embedding) VALUES (?,?,?,?::vector)",
                                userId, content, role, vecStr
                            );
                        }
                    }
            }
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
            logger.log("WARN", "PgVector append failed: " + e.getMessage());
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
            // 1. 语义召回（pgvector，取更宽的范围供 RRF 融合）
            List<MemoryResult> semanticResults = semanticSearch(userId, vec, 20);

            // 2. 关键词召回（中文子串 + 词匹配）
            List<MemoryResult> keywordResults = keywordSearch(userId, query, 20);

            // 3. RRF 融合
            Map<String, Double> rrfScores = mergeRrf(semanticResults, keywordResults);

            // 4. The existing full score; emotion/layer have no direct score term.
            List<MemoryResult> ranked = rankFullCandidates(semanticResults, keywordResults, rrfScores, topK, null);
            touchAccessed(userId, ranked);
            return ranked;
        } catch (Exception e) {
            logger.log("WARN", "PgVector search failed: " + e.getMessage());
            return List.of();
        }
    }

    /** Isolated SELECT-only path. Production search signatures and side effects are unchanged. */
    @Transactional(readOnly = true)
    public RetrievalDebugResult searchForEvaluation(String userId, String query, RetrievalMode mode, int topK) {
        if (!"eval_test_user".equals(userId)) throw new SecurityException("Only eval_test_user is allowed");
        if (query == null || query.isBlank() || mode == null || !Set.of(1, 3, 5, 10).contains(topK)) {
            throw new IllegalArgumentException("Invalid evaluation search parameters");
        }
        float[] vec = mode == RetrievalMode.KEYWORD_ONLY ? null : evaluationEmbedding(query);
        try {
            List<MemoryResult> semantic = mode == RetrievalMode.KEYWORD_ONLY
                ? List.of() : semanticSearchStrict(userId, vec, 20);
            List<MemoryResult> keyword = mode == RetrievalMode.VECTOR_ONLY
                ? List.of() : keywordSearchStrict(userId, query, 20);
            Map<String, Double> rrf = mode == RetrievalMode.RRF || mode == RetrievalMode.FULL
                || mode == RetrievalMode.FULL_RRF_NORM
                ? mergeRrf(semantic, keyword) : null;
            Map<String, RerankComponents> observedScores = new HashMap<>();
            Map<String, NormalizedRerankComponents> observedNormalizedScores = new HashMap<>();
            List<MemoryResult> selected = switch (mode) {
                case KEYWORD_ONLY -> keyword.stream().limit(topK).toList();
                case VECTOR_ONLY -> semantic.stream().limit(topK).toList();
                case RRF -> mergeCandidates(semantic, keyword).values().stream()
                    .sorted((a, b) -> Double.compare(rrf.get(contentId(b)), rrf.get(contentId(a))))
                    .limit(topK).toList();
                case FULL -> rankFullCandidates(semantic, keyword, rrf, topK, observedScores);
                case FULL_RRF_NORM -> rankFullRrfNormalizedCandidates(
                    semantic, keyword, rrf, topK, observedNormalizedScores);
            };
            Map<String, Integer> vectorRanks = ranks(semantic);
            Map<String, Integer> keywordRanks = ranks(keyword);
            Map<String, MemoryResult> semanticById = mergeCandidates(semantic, List.of());
            List<RetrievalDebugResult.Entry> entries = selected.stream().map(r -> {
                String id = contentId(r);
                Integer keywordRank = keywordRanks.get(id);
                Double keywordScore = keywordRank == null ? null : matchScore(r.content(), query);
                RerankComponents score = mode == RetrievalMode.FULL
                    ? observedScores.computeIfAbsent(id, ignored -> calculateRerankComponents(r, rrf.get(id)))
                    : null;
                NormalizedRerankComponents normalizedScore = mode == RetrievalMode.FULL_RRF_NORM
                    ? observedNormalizedScores.computeIfAbsent(id,
                        ignored -> calculateNormalizedRerankComponents(r, rrf.get(id)))
                    : null;
                Double finalScore = switch (mode) {
                    case KEYWORD_ONLY -> keywordScore;
                    case VECTOR_ONLY -> null;
                    case RRF -> rrf.get(id);
                    case FULL -> score.finalScore();
                    case FULL_RRF_NORM -> normalizedScore.finalScore();
                };
                RerankComponents displayedScore = score != null ? score
                    : normalizedScore == null ? null : normalizedScore.base();
                return new RetrievalDebugResult.Entry(
                    r.id(), r.content(), vectorRanks.get(id), keywordRank, keywordScore,
                    semanticById.containsKey(id) ? semanticById.get(id).distance() : null,
                    rrf == null ? null : rrf.get(id),
                    normalizedScore == null ? null : normalizedScore.rrfNormalized(),
                    r.importance(), r.confidence(), r.layer(), r.emotion(),
                    r.createdAt() == null ? null : r.createdAt().toInstant().toString(),
                    displayedScore == null ? null : displayedScore.timeScore(),
                    displayedScore == null ? null : displayedScore.importanceContribution(),
                    displayedScore == null ? null : displayedScore.confidenceContribution(),
                    displayedScore == null ? null : displayedScore.highImportanceBonus(), finalScore);
            }).toList();
            return new RetrievalDebugResult("OK", mode.wireName(), topK, entries);
        } catch (DataAccessException e) {
            throw new EvaluationFailure("SQL_FAILED", "PostgreSQL/pgvector retrieval failed", e);
        } catch (RuntimeException e) {
            throw new EvaluationFailure("RETRIEVAL_FAILED", "Evaluation retrieval failed", e);
        }
    }

    private float[] evaluationEmbedding(String query) {
        float[] vec;
        try {
            vec = embedService.embed(query);
        } catch (RuntimeException e) {
            throw new EvaluationFailure("EMBEDDING_FAILED", "Query embedding failed", e);
        }
        if (vec == null) throw new EvaluationFailure("EMBEDDING_FAILED", "Query embedding failed", null);
        if (vec.length != 1024) {
            throw new EvaluationFailure("INVALID_EMBEDDING", "A 1024-dimensional query embedding is required", null);
        }
        boolean nonzero = false;
        for (float value : vec) {
            if (!Float.isFinite(value)) {
                throw new EvaluationFailure("INVALID_EMBEDDING", "Query embedding contains non-finite values", null);
            }
            nonzero |= value != 0;
        }
        if (!nonzero) throw new EvaluationFailure("INVALID_EMBEDDING", "Query embedding is a zero vector", null);
        return vec;
    }

    public static final class EvaluationFailure extends RuntimeException {
        private final String code;

        public EvaluationFailure(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() { return code; }
    }

    private Map<String, Double> mergeRrf(List<MemoryResult> semantic, List<MemoryResult> keyword) {
        Map<String, Double> scores = new LinkedHashMap<>();
        double k = 60;
        for (int i = 0; i < semantic.size(); i++) {
            scores.merge(contentId(semantic.get(i)), 1.0 / (k + i + 1), Double::sum);
        }
        for (int i = 0; i < keyword.size(); i++) {
            scores.merge(contentId(keyword.get(i)), 1.0 / (k + i + 1), Double::sum);
        }
        return scores;
    }

    private Map<String, MemoryResult> mergeCandidates(List<MemoryResult> semantic, List<MemoryResult> keyword) {
        var allResults = new ArrayList<>(semantic);
        allResults.addAll(keyword);
        return allResults.stream().collect(java.util.stream.Collectors.toMap(
            r -> contentId(r), r -> r, (a, b) -> a));
    }

    private Map<String, Integer> ranks(List<MemoryResult> results) {
        Map<String, Integer> ranks = new HashMap<>();
        for (int i = 0; i < results.size(); i++) ranks.putIfAbsent(contentId(results.get(i)), i + 1);
        return ranks;
    }

    private List<MemoryResult> rankFullCandidates(
        List<MemoryResult> semantic, List<MemoryResult> keyword, Map<String, Double> rrf,
        int topK, Map<String, RerankComponents> observedScores
    ) {
        return mergeCandidates(semantic, keyword).values().stream()
            .sorted((a, b) -> {
                double sa = observedRerankScore(a, rrf.getOrDefault(contentId(a), 0.0), observedScores);
                double sb = observedRerankScore(b, rrf.getOrDefault(contentId(b), 0.0), observedScores);
                return Double.compare(sb, sa);
            }).limit(topK).toList();
    }

    private double observedRerankScore(MemoryResult r, double rrf, Map<String, RerankComponents> observed) {
        if (observed == null) return rerankScore(r, rrf);
        RerankComponents components = calculateRerankComponents(r, rrf);
        observed.put(contentId(r), components);
        return components.finalScore();
    }

    /** Evaluation-only H1 ranking. Production and existing FULL ranking do not call this path. */
    private List<MemoryResult> rankFullRrfNormalizedCandidates(
        List<MemoryResult> semantic, List<MemoryResult> keyword, Map<String, Double> rrf,
        int topK, Map<String, NormalizedRerankComponents> observedScores
    ) {
        return mergeCandidates(semantic, keyword).values().stream()
            .sorted((a, b) -> {
                double sa = observedNormalizedRerankScore(
                    a, rrf.getOrDefault(contentId(a), 0.0), observedScores);
                double sb = observedNormalizedRerankScore(
                    b, rrf.getOrDefault(contentId(b), 0.0), observedScores);
                return Double.compare(sb, sa);
            }).limit(topK).toList();
    }

    private double observedNormalizedRerankScore(
        MemoryResult r, double rrf, Map<String, NormalizedRerankComponents> observed
    ) {
        NormalizedRerankComponents components = calculateNormalizedRerankComponents(r, rrf);
        observed.put(contentId(r), components);
        return components.finalScore();
    }

    static double normalizeRrfScore(double rrfScore) {
        return Math.max(0.0, Math.min(1.0, rrfScore / RRF_MAX));
    }

    private NormalizedRerankComponents calculateNormalizedRerankComponents(
        MemoryResult r, double rrfScore
    ) {
        RerankComponents base = calculateRerankComponents(r, rrfScore);
        double rrfNormalized = normalizeRrfScore(rrfScore);
        double finalScore = rrfNormalized * 0.5 + base.timeScore() * 0.2
            + base.importanceContribution() + base.confidenceContribution()
            + base.highImportanceBonus();
        return new NormalizedRerankComponents(rrfNormalized, base, finalScore);
    }

    private record NormalizedRerankComponents(
        double rrfNormalized, RerankComponents base, double finalScore
    ) {}

    private List<MemoryResult> semanticSearch(String userId, String query, int limit) {
        return semanticSearch(userId, embedService.embed(query), limit);
    }

    private List<MemoryResult> semanticSearch(String userId, float[] vec, int limit) {
        if (vec == null) return List.of();
        try {
            return semanticSearchStrict(userId, vec, limit);
        } catch (Exception e) { return List.of(); }
    }

    private List<MemoryResult> semanticSearchStrict(String userId, float[] vec, int limit) {
        String vecStr = EmbeddingService.toPgVectorString(vec);
        return jdbc.query(
                "SELECT id, content, role, created_at, event_date, event_at, event_timezone, event_precision, importance, COALESCE(confidence,1.0) AS confidence, layer, emotion, "
                + "embedding <=> ?::vector AS distance FROM long_term_memory WHERE user_id = ? "
                + "AND importance * EXP(-EXTRACT(EPOCH FROM (NOW() - COALESCE(last_accessed, created_at))) / 3600.0 "
                + "  / (CASE WHEN layer = 2 THEN 5.0 ELSE 1.0 END * 24 + 1)) > ? "
                + "ORDER BY embedding <=> ?::vector LIMIT ?",
                ps -> { ps.setString(1, vecStr); ps.setString(2, userId);
                        ps.setDouble(3, RETENTION_MIN); ps.setString(4, vecStr); ps.setInt(5, limit); },
                (rs, rn) -> new MemoryResult(rs.getString("id"), rs.getString("content"), rs.getString("role"),
                    rs.getTimestamp("created_at"), rs.getDate("event_date"), rs.getTimestamp("event_at"),
                    rs.getString("event_timezone"), rs.getString("event_precision"), rs.getDouble("distance"),
                    rs.getDouble("importance"), rs.getDouble("confidence"), rs.getString("emotion"), rs.getInt("layer"), 1.0)
        );
    }

    /** 关键词匹配：中文子串 + 2-4字分词匹配 */
    private List<MemoryResult> keywordSearch(String userId, String query, int limit) {
        try {
            return keywordSearchStrict(userId, query, limit);
        } catch (Exception e) { return List.of(); }
    }

    private List<MemoryResult> keywordSearchStrict(String userId, String query, int limit) {
        return jdbc.query(
                "SELECT id, content, role, created_at, event_date, event_at, event_timezone, event_precision, importance, COALESCE(confidence,1.0) AS confidence, layer, emotion, 0.5 AS distance "
                + "FROM long_term_memory WHERE user_id = ? "
                + "AND importance * EXP(-EXTRACT(EPOCH FROM (NOW() - COALESCE(last_accessed, created_at))) / 3600.0 "
                + "  / (CASE WHEN layer = 2 THEN 5.0 ELSE 1.0 END * 24 + 1)) > ? "
                + "ORDER BY importance DESC LIMIT 100",
                ps -> { ps.setString(1, userId); ps.setDouble(2, RETENTION_MIN); },
                (rs, rn) -> new MemoryResult(rs.getString("id"), rs.getString("content"), rs.getString("role"),
                    rs.getTimestamp("created_at"), rs.getDate("event_date"), rs.getTimestamp("event_at"),
                    rs.getString("event_timezone"), rs.getString("event_precision"), 0.5, rs.getDouble("importance"), rs.getDouble("confidence"), rs.getString("emotion"),
                    rs.getInt("layer"), 1.0)
            ).stream().filter(r -> matchScore(r.content(), query) > 0)
              .sorted((a, b) -> Double.compare(matchScore(b.content(), query), matchScore(a.content(), query)))
              .limit(limit).toList();
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
        return calculateRerankComponents(r, rrfScore).finalScore();
    }

    private RerankComponents calculateRerankComponents(MemoryResult r, double rrfScore) {
        long elapsed = System.currentTimeMillis() - (r.createdAt() != null ? r.createdAt().getTime() : 0);
        double hours = elapsed / 3600000.0;
        double strength = r.importance() >= 0.6 ? 5.0 : 1.0;
        double timeDecay = Math.exp(-hours / (strength * 24 + 1));
        double importanceContribution = r.importance() * 0.2;
        double confidenceContribution = r.confidence() * 0.05;
        double highImportanceBonus = r.importance() >= 0.6 ? 0.05 : 0;
        double finalScore = rrfScore * 0.5 + timeDecay * 0.2 + importanceContribution
            + confidenceContribution + highImportanceBonus;
        return new RerankComponents(timeDecay, importanceContribution, confidenceContribution,
            highImportanceBonus, finalScore);
    }

    private record RerankComponents(double timeScore, double importanceContribution,
                                    double confidenceContribution, double highImportanceBonus,
                                    double finalScore) {}

    private void touchAccessed(String userId, List<MemoryResult> results) {
        for (MemoryResult result : results) {
            if (result.id() == null || result.id().isBlank()) continue;
            try {
                jdbc.update("UPDATE long_term_memory SET access_count=COALESCE(access_count,0)+1, "
                    + "last_accessed=NOW() WHERE user_id=? AND id=?::bigint", userId, result.id());
            } catch (Exception e) {
                logger.log("DEBUG", "PgVector access refresh failed: " + e.getMessage());
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
            return jdbc.update(
                "DELETE FROM long_term_memory WHERE user_id = ? "
                + "AND importance * EXP(-EXTRACT(EPOCH FROM (NOW() - COALESCE(last_accessed, created_at))) / 3600.0 "
                + "  / (CASE WHEN layer = 2 THEN 5.0 ELSE 1.0 END * 24 + 1)) < ? "
                + "AND access_count < 3",
                userId, RETENTION_MIN
            );
        } catch (Exception e) {
            logger.log("WARN", "PgVector prune failed: " + e.getMessage());
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
