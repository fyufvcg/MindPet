package service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import util.Logger;

import java.sql.Timestamp;
import java.sql.Date;
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
    private final MemoryRetrievalPolicy retrievalPolicy;

    public SqliteMemoryService(JdbcTemplate jdbc, EmbeddingService embedService,
                                 VectorSearchService vectorSearch, Logger logger) {
        this(jdbc, embedService, vectorSearch, logger, MemoryRetrievalPolicy.defaults());
    }

    @Autowired
    public SqliteMemoryService(JdbcTemplate jdbc, EmbeddingService embedService,
                                 VectorSearchService vectorSearch, Logger logger, MemoryRetrievalPolicy retrievalPolicy) {
        this.jdbc = jdbc;
        this.embedService = embedService;
        this.vectorSearch = vectorSearch;
        this.logger = logger;
        this.retrievalPolicy = retrievalPolicy;
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
        List<MemoryResult> results = searchCandidates(userId, query, vec, topK);
        touchAccessed(userId, results);
        return results;
    }

    /** Candidate retrieval is read-only; the final context owner records actual use. */
    public List<MemoryResult> searchCandidates(String userId, String query, float[] vec, int topK) {
        return searchRoutes(userId, query, vec, topK, true, true).stream().map(RoutedMemory::memory).toList();
    }

    public record RoutedMemory(MemoryResult memory, int keywordRank, int vectorRank,
                               double lexical, double distance, double preference, double score) { }

    public List<RoutedMemory> searchRoutes(String userId, String query, float[] vec, int topK,
                                           boolean keywordEnabled, boolean vectorEnabled) {
        if (userId == null || userId.isBlank() || query == null || query.isBlank() || topK <= 0) return List.of();
        try {
            List<MemoryResult> eligible = loadSearchableMemories(userId).stream()
                .filter(r -> !retrievalPolicy.applyRetention() || r.retentionRate() > RETENTION_MIN).toList();
            int routeLimit = Math.max(40, topK);
            var lexical = MemoryRetrievalRanking.lexicalQuery(query, eligible.stream().map(MemoryResult::content).toList());
            var byId = eligible.stream().collect(java.util.stream.Collectors.toMap(MemoryResult::id, r -> r));
            List<MemoryResult> semanticResults = new ArrayList<>();
            if (vectorEnabled && vec != null && vectorSearch != null) {
                try {
                    // Apply eligibility before truncation so faded hits cannot consume the candidate budget.
                    for (var match : vectorSearch.search("long_term_memory", userId, vec,
                            Math.max(1, count(userId)))) {
                        MemoryResult row = byId.get(match.id());
                        if (row != null && Double.isFinite(match.distance()) && match.distance() <= 0.65) {
                            semanticResults.add(withDistance(row, match.distance()));
                            if (semanticResults.size() >= routeLimit) break;
                        }
                    }
                } catch (Exception e) {
                    logger.log("WARN", "语义召回失败，保留关键词召回: " + e.getMessage());
                }
            }

            List<MemoryResult> keywordResults = !keywordEnabled ? List.of() : eligible.stream()
                .filter(r -> lexical.score(r.content()) >= 0.25)
                .sorted(java.util.Comparator.<MemoryResult>comparingDouble(
                    r -> lexical.score(r.content())).reversed()
                    .thenComparing(MemoryResult::id))
                .limit(routeLimit).toList();

            // 3. RRF 融合
            java.util.Map<String, Double> rrfScores = new java.util.LinkedHashMap<>();
            java.util.Map<String, Integer> keywordRanks = new java.util.LinkedHashMap<>(), vectorRanks = new java.util.LinkedHashMap<>();
            double k = 60;
            for (int i = 0; i < semanticResults.size(); i++) {
                rrfScores.merge(contentId(semanticResults.get(i)), 1.0 / (k + i + 1), Double::sum);
                vectorRanks.put(contentId(semanticResults.get(i)), i + 1);
            }
            for (int i = 0; i < keywordResults.size(); i++) {
                rrfScores.merge(contentId(keywordResults.get(i)), 1.0 / (k + i + 1), Double::sum);
                keywordRanks.put(contentId(keywordResults.get(i)), i + 1);
            }

            // Query relevance dominates; metadata provides bounded tie preferences.
            var allResults = new java.util.ArrayList<>(semanticResults);
            allResults.addAll(keywordResults);
            var byContent = allResults.stream()
                .collect(java.util.stream.Collectors.toMap(
                    r -> contentId(r), r -> r, (a, b) -> a));

            List<MemoryResult> ranked = byContent.values().stream()
                .sorted((a, b) -> {
                    double sa = rerankScore(a, lexical, rrfScores.getOrDefault(contentId(a), 0.0), keywordEnabled);
                    double sb = rerankScore(b, lexical, rrfScores.getOrDefault(contentId(b), 0.0), keywordEnabled);
                    int compared = Double.compare(sb, sa);
                    return compared != 0 ? compared : a.id().compareTo(b.id());
                })
                .limit(topK)
                .toList();
            return ranked.stream().map(r -> new RoutedMemory(r, keywordRanks.getOrDefault(r.id(), 0),
                vectorRanks.getOrDefault(r.id(), 0), keywordEnabled ? lexical.score(r.content()) : 0, r.distance(),
                metadataPreference(r), rerankScore(r, lexical, rrfScores.getOrDefault(r.id(), 0.0), keywordEnabled))).toList();
        } catch (Exception e) {
            logger.log("WARN", "SQLite vector search failed: " + e.getMessage());
            return List.of();
        }
    }

    private List<MemoryResult> loadSearchableMemories(String userId) {
        return jdbc.query("SELECT id,content,role,created_at,event_date,event_at,event_timezone,event_precision,"
                + "importance,COALESCE(confidence,1.0) confidence,layer,emotion,last_accessed "
                + "FROM long_term_memory WHERE user_id=? AND searchable=1 ORDER BY id",
            (rs, rn) -> new MemoryResult(rs.getString("id"), rs.getString("content"), rs.getString("role"),
                retrievalPolicy.readStorageTimestamp(rs.getString("created_at")), readEventDate(rs.getString("event_date")),
                readEventTimestamp(rs.getString("event_at")),
                rs.getString("event_timezone"), rs.getString("event_precision"), Double.POSITIVE_INFINITY,
                rs.getDouble("importance"), rs.getDouble("confidence"), rs.getString("emotion"), rs.getInt("layer"),
                retrievalPolicy.rawRetention(retrievalPolicy.readStorageTimestamp(rs.getString("last_accessed")),
                    retrievalPolicy.readStorageTimestamp(rs.getString("created_at")),
                    rs.getDouble("importance"), rs.getInt("layer"))), userId);
    }

    private MemoryResult withDistance(MemoryResult r, double distance) {
        return new MemoryResult(r.id(), r.content(), r.role(), r.createdAt(), r.eventDate(), r.eventAt(),
            r.eventTimezone(), r.eventPrecision(), distance, r.importance(), r.confidence(), r.emotion(), r.layer(), r.retentionRate());
    }

    /** Supports ISO fixture dates and the epoch milliseconds written by SQLite JDBC setDate. */
    private Date readEventDate(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Date.valueOf(java.time.LocalDate.parse(value)); }
        catch (java.time.DateTimeException e) {
            try { return new Date(Long.parseLong(value)); }
            catch (NumberFormatException invalid) {
                logger.log("WARN", "记忆事件日期无效: " + value);
                return null;
            }
        }
    }

    private Timestamp readEventTimestamp(String value) {
        if (value == null || value.isBlank()) return null;
        try { return new Timestamp(Long.parseLong(value)); }
        catch (NumberFormatException ignored) { }
        try { return Timestamp.valueOf(LocalDateTime.parse(value.trim().replace(' ', 'T'))); }
        catch (java.time.DateTimeException ignored) { }
        Timestamp instant = retrievalPolicy.readStorageTimestamp(value);
        if (instant == null) logger.log("WARN", "记忆事件时间无效: " + value);
        return instant;
    }

    private double metadataPreference(MemoryResult r) {
        double recency = Math.exp(-retrievalPolicy.ageHours(r.createdAt()) / (r.layer() == 2 ? 121.0 : 25.0));
        return .5 * r.importance() + .3 * recency + .2 * r.confidence();
    }
    private double rerankScore(MemoryResult r, MemoryRetrievalRanking.LexicalQuery lexical, double rrfScore, boolean keywordEnabled) {
        return MemoryRetrievalRanking.retrievalScore(rrfScore, 2,
            MemoryRetrievalRanking.relevance(keywordEnabled ? lexical.score(r.content()) : 0, r.distance()), metadataPreference(r));
    }

    private void touchAccessed(String userId, List<MemoryResult> results) {
        if (!retrievalPolicy.refreshAccess()) return;
        for (MemoryResult result : results) {
            if (result.id() == null || result.id().isBlank()) continue;
            try {
                jdbc.update("UPDATE long_term_memory SET access_count=COALESCE(access_count,0)+1, "
                    + "last_accessed=? WHERE user_id=? AND id=?", retrievalPolicy.sqlNow(), userId, result.id());
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
            List<PruneCandidate> candidates = jdbc.query(
                "SELECT id,content,importance,layer,last_accessed,created_at FROM long_term_memory "
                    + "WHERE user_id=? AND searchable=1 AND access_count<3",
                (rs, row) -> new PruneCandidate(rs.getString("id"), rs.getString("content"),
                    rs.getDouble("importance"), rs.getInt("layer"), rs.getTimestamp("last_accessed"),
                    rs.getTimestamp("created_at")), userId);
            int removed = 0;
            for (PruneCandidate row : candidates) {
                if (retention(row.lastAccessed(), row.createdAt(), row.importance(), row.layer()) >= RETENTION_MIN) continue;
                String id = row.id();
                int changed = jdbc.update("UPDATE long_term_memory SET searchable=0 WHERE user_id=? AND id=? AND searchable=1",
                    userId, id);
                if (changed == 0) continue;
                jdbc.update("UPDATE memory_retrieval_unit SET searchable=0,status='inactive',"
                        + "compaction_version=compaction_version+1,updated_at=CURRENT_TIMESTAMP "
                        + "WHERE user_id=? AND unit_type='raw_memory' AND id IN ("
                        + "SELECT unit_id FROM memory_retrieval_source WHERE source_type='long_term_memory' AND source_id=?) "
                        + "AND NOT EXISTS (SELECT 1 FROM memory_retrieval_source remaining "
                        + "JOIN long_term_memory active ON CAST(active.id AS TEXT)=remaining.source_id "
                        + "WHERE remaining.unit_id=memory_retrieval_unit.id "
                        + "AND remaining.source_type='long_term_memory' AND active.user_id=? AND active.searchable=1)",
                    userId, id, userId);
                int tokens = MemoryCorpusCompactionService.tokenCount(row.content());
                try {
                    jdbc.update("INSERT INTO memory_compaction_log(user_id,batch_sequence,action,unit_id,source_id,"
                            + "tokens_before,tokens_after,reason) VALUES(?,0,'RETIRE',?,?,?,0,'retention_prune')",
                        userId, id, id, tokens);
                } catch (Exception ignored) { }
                removed++;
            }
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

    private record PruneCandidate(String id, String content, double importance, int layer,
                                  Timestamp lastAccessed, Timestamp createdAt) {}

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
