package service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class UserProfileService {

    private final JdbcTemplate jdbc;
    private final ProfileProjectionService projectionService;

    public UserProfileService(JdbcTemplate jdbc, ProfileProjectionService projectionService) {
        this.jdbc = jdbc;
        this.projectionService = projectionService;
    }

    public void save(String userId, String category, String key, String value) {
        jdbc.update(
            "INSERT INTO user_profile (user_id, category, prop_key, prop_value, updated_at) " +
            "VALUES (?,?,?,?,CURRENT_TIMESTAMP) ON CONFLICT (user_id, category, prop_key) " +
            "DO UPDATE SET prop_value=excluded.prop_value, updated_at=CURRENT_TIMESTAMP",
            userId, category, key, value
        );
    }

    /** Returns an explicitly saved user timezone, if one is present. */
    public String getConfiguredTimezone(String userId) {
        if (userId == null || userId.isBlank()) return null;
        List<String> values = jdbc.query("SELECT prop_value FROM user_profile WHERE user_id=? "
                + "AND prop_key IN ('timezone','time_zone','time zone','時區') "
                + "ORDER BY CASE category WHEN 'preference' THEN 0 WHEN 'identity' THEN 1 ELSE 2 END,updated_at DESC LIMIT 1",
            (rs, row) -> rs.getString(1), userId);
        return values.isEmpty() ? null : values.get(0);
    }

    /** Full profile as formatted text for system prompt */
    public String getProfileContext(String userId) {
        String projected = projectionService.context(userId);
        Set<String> projectedSlots = new HashSet<>();
        for (Map<String, Object> current : projectionService.list(userId)) {
            projectedSlots.add(String.valueOf(current.getOrDefault("slot_key", "")));
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT category, prop_key, prop_value FROM user_profile WHERE user_id=? ORDER BY category",
            userId
        );

        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (var row : rows) {
            String cat = (String) row.get("category");
            String key = String.valueOf(row.get("prop_key"));
            if (projectedSlots.contains(key)) continue;
            String catLabel = switch (cat) {
                case "identity"    -> "基础身份";
                case "preference"  -> "长期偏好";
                case "experience"  -> "重要经历";
                case "state"       -> "当前状态";
                default -> cat;
            };
            grouped.computeIfAbsent(catLabel, k -> new ArrayList<>())
                .add(key + ": " + row.get("prop_value"));
        }

        StringBuilder sb = new StringBuilder();
        if (projected != null && !projected.isBlank()) sb.append(projected);
        if (!grouped.isEmpty()) {
            if (!sb.isEmpty()) sb.append("\n\n");
            sb.append("【长期画像】\n");
        }
        for (var entry : grouped.entrySet()) {
            sb.append("[").append(entry.getKey()).append("]\n");
            for (String line : entry.getValue()) sb.append("  ").append(line).append("\n");
        }
        return sb.isEmpty() ? null : sb.toString().trim();
    }

    /** Compatibility writer for manual profile editing. New curator writes use fact projection. */
    public void saveCurrent(String userId, String slotKey, String value, long sourceFactId,
                            double confidence, String validFrom, String validTo) {
        jdbc.update("INSERT INTO user_profile_current(user_id,slot_key,value,source_fact_id,confidence,valid_from,valid_to,updated_at) "
                + "VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP) ON CONFLICT(user_id,slot_key) DO UPDATE SET "
                + "value=excluded.value,source_fact_id=excluded.source_fact_id,confidence=excluded.confidence,"
                + "valid_from=excluded.valid_from,valid_to=excluded.valid_to,updated_at=CURRENT_TIMESTAMP",
            userId, slotKey, value, sourceFactId <= 0 ? null : sourceFactId, confidence, validFrom, validTo);
    }

    /** Get relevant memories for LLM context (used by AssistantBot) */
    public String getRelevantMemoryContext(String userId, String query, int limit) {
        // Now handled by SqliteMemoryService — keep stub for compatibility.
        return null;
    }

    public String getAllProfiles(String userId) {
        return getProfileContext(userId);
    }
}
