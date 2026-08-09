package service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class UserProfileService {

    private final JdbcTemplate jdbc;

    public UserProfileService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(String userId, String category, String key, String value) {
        jdbc.update(
            "INSERT INTO user_profile (user_id, category, prop_key, prop_value, updated_at) " +
            "VALUES (?,?,?,?,NOW()) ON CONFLICT (user_id, category, prop_key) " +
            "DO UPDATE SET prop_value=?, updated_at=NOW()",
            userId, category, key, value, value
        );
    }

    /** Full profile as formatted text for system prompt */
    public String getProfileContext(String userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT category, prop_key, prop_value FROM user_profile WHERE user_id=? ORDER BY category",
            userId
        );
        if (rows.isEmpty()) return null;

        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (var row : rows) {
            String cat = (String) row.get("category");
            String catLabel = switch (cat) {
                case "identity"    -> "基础身份";
                case "preference"  -> "长期偏好";
                case "experience"  -> "重要经历";
                case "state"       -> "当前状态";
                default -> cat;
            };
            grouped.computeIfAbsent(catLabel, k -> new ArrayList<>())
                .add(row.get("prop_key") + ": " + row.get("prop_value"));
        }

        StringBuilder sb = new StringBuilder("【用户画像】\n");
        for (var entry : grouped.entrySet()) {
            sb.append("[").append(entry.getKey()).append("]\n");
            for (String line : entry.getValue()) sb.append("  ").append(line).append("\n");
        }
        return sb.toString().trim();
    }

    /** Get relevant memories for LLM context (used by AssistantBot) */
    public String getRelevantMemoryContext(String userId, String query, int limit) {
        // Now handled by PgVectorMemoryService — keep stub for compatibility
        return null;
    }

    public String getAllProfiles(String userId) {
        return getProfileContext(userId);
    }
}
