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
    private final Logger logger;

    public UserInsightService(JdbcTemplate jdbc, EmbeddingService embedService, Logger logger) {
        this.jdbc = jdbc;
        this.embedService = embedService;
        this.logger = logger;
    }

    /** Store a new insight with embedding */
    public boolean save(String userId, String insight, String context) {
        try {
            float[] vec = embedService.embed(insight);
            if (vec == null) return false;
            String vecStr = EmbeddingService.toPgVectorString(vec);
            int inserted = jdbc.update(
                "INSERT INTO user_insight (user_id, insight, context, embedding) " +
                "SELECT ?,?,?,?::vector WHERE NOT EXISTS (" +
                "SELECT 1 FROM user_insight WHERE user_id=? AND insight=?)",
                userId, insight, context, vecStr, userId, insight
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
        try {
            float[] vec = embedService.embed(insight);
            if (vec == null) return false;
            String vecStr = EmbeddingService.toPgVectorString(vec);
            int inserted = jdbc.update(
                "INSERT INTO llm_growth (user_id, category, insight, context, embedding) " +
                "SELECT ?,?,?,?,?::vector WHERE NOT EXISTS (" +
                "SELECT 1 FROM llm_growth WHERE user_id=? AND category=? AND insight=?)",
                userId, category, insight, context, vecStr, userId, category, insight
            );
            return inserted > 0;
        } catch (Exception e) {
            logger.log("ERROR", "保存 Growth 失败: " + e.getMessage());
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
            String vecStr = EmbeddingService.toPgVectorString(vec);

            List<String> items = jdbc.query(
                "SELECT category, insight, embedding <=> ?::vector AS distance " +
                "FROM llm_growth WHERE user_id = ? AND embedding <=> ?::vector < ? " +
                "ORDER BY embedding <=> ?::vector LIMIT 3",
                ps -> {
                    ps.setString(1, vecStr); ps.setString(2, userId);
                    ps.setString(3, vecStr); ps.setDouble(4, DISTANCE_THRESHOLD);
                    ps.setString(5, vecStr);
                },
                (rs, rowNum) -> {
                    String cat = switch (rs.getString("category")) {
                        case "personality" -> "性格"; case "preference" -> "喜好";
                        case "knowledge" -> "认知"; case "style" -> "风格";
                        default -> rs.getString("category");
                    };
                    return String.format("- [%s] %s", cat, rs.getString("insight"));
                }
            );

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
            String vecStr = EmbeddingService.toPgVectorString(vec);

            List<String> insights = jdbc.query(
                "SELECT insight, context, embedding <=> ?::vector AS distance " +
                "FROM user_insight WHERE user_id = ? AND embedding <=> ?::vector < ? " +
                "ORDER BY embedding <=> ?::vector LIMIT ?",
                ps -> {
                    ps.setString(1, vecStr); ps.setString(2, userId);
                    ps.setString(3, vecStr); ps.setDouble(4, DISTANCE_THRESHOLD);
                    ps.setString(5, vecStr); ps.setInt(6, TOP_K);
                },
                (rs, rowNum) -> {
                    double d = rs.getDouble("distance");
                    return String.format("- (%.2f) %s", d, rs.getString("insight"));
                }
            );

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
