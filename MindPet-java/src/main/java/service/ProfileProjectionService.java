package service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** Projects active, high-confidence facts into the user current profile. */
@Service
public class ProfileProjectionService {

    private final JdbcTemplate jdbc;

    public ProfileProjectionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public boolean projectFact(String userId, long factId) {
        if (userId == null || userId.isBlank() || factId <= 0) return false;
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT predicate,value_text,confidence,valid_from,valid_to,status "
                + "FROM memory_fact WHERE id=? AND user_id=?", factId, userId);
        if (rows.isEmpty()) return false;
        Map<String, Object> fact = rows.get(0);
        String predicate = String.valueOf(fact.getOrDefault("predicate", ""));
        String status = String.valueOf(fact.getOrDefault("status", ""));
        double confidence = ((Number) fact.getOrDefault("confidence", 0.0)).doubleValue();
        if (!isProjectable(predicate) || !"active".equals(status) || confidence < 0.85) return false;

        jdbc.update("INSERT INTO user_profile_current(user_id,slot_key,value,source_fact_id,confidence,valid_from,valid_to,updated_at) "
                + "VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP) "
                + "ON CONFLICT(user_id,slot_key) DO UPDATE SET value=excluded.value,source_fact_id=excluded.source_fact_id,"
                + "confidence=excluded.confidence,valid_from=excluded.valid_from,valid_to=excluded.valid_to,updated_at=CURRENT_TIMESTAMP",
            userId, predicate, String.valueOf(fact.getOrDefault("value_text", "")), factId, confidence,
            fact.get("valid_from"), fact.get("valid_to"));
        return true;
    }

    public List<Map<String, Object>> list(String userId) {
        return jdbc.queryForList("SELECT user_id,slot_key,value,source_fact_id,confidence,valid_from,valid_to,updated_at "
            + "FROM user_profile_current WHERE user_id=? ORDER BY slot_key", userId);
    }

    public String context(String userId) {
        List<Map<String, Object>> rows = list(userId);
        if (rows.isEmpty()) return null;
        StringBuilder out = new StringBuilder("【用户当前画像】\n");
        for (Map<String, Object> row : rows) {
            out.append("  ").append(row.get("slot_key")).append(": ")
                .append(row.get("value")).append("\n");
        }
        return out.toString().trim();
    }

    private boolean isProjectable(String predicate) {
        return switch (predicate) {
            case "current_location", "home_location", "occupation_current",
                "relationship_status_current", "current_project" -> true;
            default -> false;
        };
    }
}
