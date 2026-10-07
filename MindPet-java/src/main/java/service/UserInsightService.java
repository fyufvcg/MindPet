package service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import util.Logger;

import java.util.List;

@Service
public class UserInsightService {

    private static final double DISTANCE_THRESHOLD = 0.3;
    private static final int TOP_K = 5;

    private final JdbcTemplate jdbc;
    private final EmbeddingService embedService;
    private final VectorSearchService vectorSearch;
    private final Logger logger;

    public UserInsightService(JdbcTemplate jdbc, EmbeddingService embedService,
                              VectorSearchService vectorSearch, Logger logger) {
        this.jdbc = service.v3.SensitivePersistenceGuard.protect(jdbc);
        this.embedService = embedService;
        this.vectorSearch = vectorSearch;
        this.logger = logger;
    }

    /** Store a new insight with embedding */
    public boolean save(String userId, String insight, String context) {
        byte[] embedding = prepareEmbedding(insight);
        if (embedding == null) return false;
        return savePreparedInsight(userId, insight, context, embedding);
    }

    /** Compute an embedding before opening a curator commit transaction. */
    public byte[] prepareEmbedding(String text) {
        float[] vector = embedService.embed(text);
        return vector == null ? null : VectorSearchService.encode(vector);
    }

    /** Store curated content even when its optional vector provider is unavailable. */
    public boolean savePreparedInsight(String userId, String insight, String context, byte[] embedding) {
        try {
            int inserted = jdbc.update(
                "INSERT INTO user_insight (user_id, insight, context, embedding) " +
                "SELECT ?,?,?,? WHERE NOT EXISTS (" +
                "SELECT 1 FROM user_insight WHERE user_id=? AND insight=?)",
                userId, insight, context, embedding, userId, insight
            );
            return inserted > 0;
        } catch (Exception e) {
            logger.log("ERROR", "保存 Insight 失败: " + e.getMessage());
            return false;
        }
    }

    public boolean insightExists(String userId, String insight) {
        try {
            Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM user_insight WHERE user_id=? AND insight=?)",
                Boolean.class, userId, insight);
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            logger.log("ERROR", "检查 Insight 重复失败: " + e.getMessage());
            return false;
        }
    }

    /** 获取用户所有 insight，供记忆馆长查重 */
    public String getAllInsights(String userId) {
        try {
            List<String> items = jdbc.query(
                "SELECT insight FROM user_insight WHERE user_id=? ORDER BY created_at DESC",
                (rs, rowNum) -> rs.getString("insight"),
                userId
            );
            if (items.isEmpty()) return null;
            StringBuilder sb = new StringBuilder("【已保存的相处经验】\n");
            for (String s : items) sb.append("- ").append(s).append("\n");
            return sb.toString().trim();
        } catch (Exception e) {
            logger.log("ERROR", "获取全量 Insight 失败: " + e.getMessage());
            return null;
        }
    }

    // ==================== LLM Growth ====================

    public boolean saveGrowth(String userId, String category, String insight, String context) {
        byte[] embedding = prepareEmbedding(insight);
        if (embedding == null) return false;
        return savePreparedGrowth(userId, category, insight, context, embedding);
    }

    /** Store curated growth with a vector prepared before its commit transaction. */
    public boolean savePreparedGrowth(String userId, String category, String insight,
                                      String context, byte[] embedding) {
        try {
            int inserted = jdbc.update(
                "INSERT INTO llm_growth (user_id, category, insight, context, embedding) " +
                "SELECT ?,?,?,?,? WHERE NOT EXISTS (" +
                "SELECT 1 FROM llm_growth WHERE user_id=? AND category=? AND insight=?)",
                userId, category, insight, context, embedding, userId, category, insight
            );
            return inserted > 0;
        } catch (Exception e) {
            logger.log("ERROR", "保存 Growth 失败: " + e.getMessage());
            return false;
        }
    }

    public boolean saveMemoryReflection(String userId, String sourceType, String sourceId,
                                        String title, String thought, String context) {
        if (userId == null || userId.isBlank() || sourceType == null || sourceType.isBlank()
                || sourceId == null || sourceId.isBlank() || thought == null || thought.isBlank()) {
            return false;
        }
        try {
            float[] vector = embedService.embed(thought);
            if (vector == null) return false;
            byte[] embedding = VectorSearchService.encode(vector);
            int updated = jdbc.update(
                "UPDATE llm_growth SET title=?,insight=?,context=?,embedding=?,updated_at=CURRENT_TIMESTAMP "
                    + "WHERE user_id=? AND category='memory_reflection' AND source_type=? AND source_id=?",
                title == null ? "" : title, thought, context == null ? "" : context, embedding,
                userId, sourceType, sourceId);
            if (updated > 0) return true;

            int inserted = jdbc.update(
                "INSERT OR IGNORE INTO llm_growth "
                    + "(user_id,category,insight,context,embedding,title,source_type,source_id,updated_at) "
                    + "VALUES (?,'memory_reflection',?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                userId, thought, context == null ? "" : context, embedding,
                title == null ? "" : title, sourceType, sourceId);
            if (inserted > 0) return true;

            return jdbc.update(
                "UPDATE llm_growth SET title=?,insight=?,context=?,embedding=?,updated_at=CURRENT_TIMESTAMP "
                    + "WHERE user_id=? AND category='memory_reflection' AND source_type=? AND source_id=?",
                title == null ? "" : title, thought, context == null ? "" : context, embedding,
                userId, sourceType, sourceId) > 0;
        } catch (Exception e) {
            logger.log("ERROR", "保存 MindPet 记忆回响失败: " + e.getMessage());
            return false;
        }
    }

    public boolean growthExists(String userId, String category, String insight) {
        try {
            Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM llm_growth WHERE user_id=? AND category=? AND insight=?)",
                Boolean.class, userId, category, insight);
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            logger.log("ERROR", "检查 Growth 重复失败: " + e.getMessage());
            return false;
        }
    }

    /** 获取用户所有 growth，供记忆馆长查重 */
    public String getAllGrowths(String userId) {
        try {
            List<String> items = jdbc.query(
                "SELECT category, insight FROM llm_growth WHERE user_id=? ORDER BY created_at DESC",
                (rs, rowNum) -> {
                    String cat = switch (rs.getString("category")) {
                        case "personality" -> "性格"; case "preference" -> "喜好";
                        case "knowledge" -> "认知"; case "style" -> "风格";
                        case "memory_reflection" -> "记忆回响";
                        default -> rs.getString("category");
                    };
                    return "- [" + cat + "] " + rs.getString("insight");
                },
                userId
            );
            if (items.isEmpty()) return null;
            StringBuilder sb = new StringBuilder("【已保存的成长记录】\n");
            for (String s : items) sb.append(s).append("\n");
            return sb.toString().trim();
        } catch (Exception e) {
            logger.log("ERROR", "获取全量 Growth 失败: " + e.getMessage());
            return null;
        }
    }

    public String getGrowthContext(String userId, String query) {
        return getGrowthContext(userId, embedService.embed(query));
    }

    public String getGrowthContext(String userId, float[] vec) {
        if (vec == null) return null;
        try {
            List<String> items = vectorSearch.search("llm_growth", userId, vec, 12).stream()
                .filter(match -> match.distance() < DISTANCE_THRESHOLD)
                .limit(3)
                .map(match -> jdbc.queryForObject(
                    "SELECT category,insight FROM llm_growth WHERE id=?",
                    (rs, rowNum) -> {
                    String cat = switch (rs.getString("category")) {
                        case "personality" -> "性格"; case "preference" -> "喜好";
                        case "knowledge" -> "认知"; case "style" -> "风格";
                        case "memory_reflection" -> "记忆回响";
                        default -> rs.getString("category");
                    };
                    return String.format("- [%s] %s", cat, rs.getString("insight"));
                    }, match.id()))
                .filter(java.util.Objects::nonNull).toList();

            if (items.isEmpty()) return null;
            StringBuilder sb = new StringBuilder("【MindPet 的自我成长】\n");
            for (String s : items) sb.append(s).append("\n");
            sb.append("请自然地体现这些成长，不要刻意宣告。");
            return sb.toString().trim();
        } catch (Exception e) {
            logger.log("ERROR", "检索 Growth 失败: " + e.getMessage());
            return null;
        }
    }

    // ==================== User Insight ====================

    /** RAG search: return top insights matching current conversation */
    public String getInsightContext(String userId, String query) {
        return getInsightContext(userId, embedService.embed(query));
    }

    /** RAG search with pre-computed embedding (avoids duplicate API calls) */
    public String getInsightContext(String userId, float[] vec) {
        if (vec == null) return null;
        try {
            List<String> insights = vectorSearch.search("user_insight", userId, vec, Math.max(TOP_K * 3, TOP_K)).stream()
                .filter(match -> match.distance() < DISTANCE_THRESHOLD)
                .limit(TOP_K)
                .map(match -> jdbc.queryForObject("SELECT insight FROM user_insight WHERE id=?",
                    String.class, match.id()))
                .filter(java.util.Objects::nonNull)
                .map(insight -> "- " + insight).toList();

            if (insights.isEmpty()) return null;
            StringBuilder sb = new StringBuilder("【与用户相处的经验】\n");
            for (String s : insights) sb.append(s).append("\n");
            return sb.toString().trim();
        } catch (Exception e) {
            logger.log("ERROR", "检索 Insight 失败: " + e.getMessage());
            return null;
        }
    }
}
