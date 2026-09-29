package service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Rebuilds each current-profile slot from the latest valid active fact. */
@Service
public class ProfileProjectionService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ProfileProjectionService(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemDefaultZone());
    }

    @Autowired
    public ProfileProjectionService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    @Transactional
    public boolean projectFact(String userId, long factId) {
        if (userId == null || userId.isBlank() || factId <= 0) return false;
        List<String> predicates = jdbc.query("SELECT predicate FROM memory_fact WHERE id=? AND user_id=?",
            (rs, row) -> rs.getString(1), factId, userId);
        return !predicates.isEmpty() && reconcileSlot(userId, predicates.get(0));
    }

    @Transactional
    public boolean reconcileSlot(String userId, String predicate) {
        if (userId == null || userId.isBlank() || !MemoryFactOntology.isProfileSlot(predicate)) return false;
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT mf.predicate,mf.id,mf.value_text,mf.confidence,mf.valid_from,mf.valid_to,mf.status,mf.scope,mf.assertion,"
                + "mf.time_status,mf.raw_time_expression,mf.event_timezone,mf.observed_at,COALESCE(ct.sequence,0) source_sequence "
                + "FROM memory_fact mf LEFT JOIN curator_turns ct ON ct.user_id=mf.user_id AND ct.turn_id=mf.source_turn_id "
                + "WHERE mf.user_id=? AND mf.predicate=? AND mf.status='active'",
            userId, predicate);

        Map<String, Object> selected = rows.stream()
            .filter(this::isProjectable)
            .sorted(Comparator
                .comparing((Map<String, Object> row) -> parseInstant(row.get("observed_at")),
                    Comparator.nullsFirst(Comparator.naturalOrder())).reversed()
                .thenComparing((Map<String, Object> row) -> number(row.get("source_sequence")), Comparator.reverseOrder())
                .thenComparing((Map<String, Object> row) -> number(row.get("confidence")), Comparator.reverseOrder())
            .thenComparing(row -> text(row.get("value_text")), Comparator.reverseOrder())
            .thenComparing((Map<String, Object> row) -> number(row.get("id")), Comparator.reverseOrder()))
            .findFirst().orElse(null);

        if (selected == null) {
            jdbc.update("DELETE FROM user_profile_current WHERE user_id=? AND slot_key=?", userId, predicate);
            return false;
        }

        jdbc.update("INSERT INTO user_profile_current(user_id,slot_key,value,source_fact_id,confidence,valid_from,valid_to,updated_at) "
                + "VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP) "
                + "ON CONFLICT(user_id,slot_key) DO UPDATE SET value=excluded.value,source_fact_id=excluded.source_fact_id,"
                + "confidence=excluded.confidence,valid_from=excluded.valid_from,valid_to=excluded.valid_to,updated_at=CURRENT_TIMESTAMP",
            userId, predicate, text(selected.get("value_text")), number(selected.get("id")),
            number(selected.get("confidence")), selected.get("valid_from"), selected.get("valid_to"));
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

    private boolean isProjectable(Map<String, Object> fact) {
        String predicate = text(fact.get("predicate"));
        String scope = text(fact.get("scope"));
        String assertion = text(fact.get("assertion"));
        double confidence = number(fact.get("confidence"));
        String timeStatus = text(fact.get("time_status"));
        String rawTime = text(fact.get("raw_time_expression"));
        return MemoryFactOntology.isProjectable(predicate, scope, assertion)
            && confidence >= 0.85
            && !"ambiguous".equals(timeStatus)
            && !("unresolved".equals(timeStatus) && !rawTime.isBlank())
            && validNow(fact);
    }

    private boolean validNow(Map<String, Object> fact) {
        ZoneId zone;
        try {
            String timezone = text(fact.get("event_timezone"));
            zone = timezone.isBlank() ? clock.getZone() : ZoneId.of(timezone);
        } catch (RuntimeException ignored) {
            zone = clock.getZone();
        }
        LocalDate today = LocalDate.now(clock.withZone(zone));
        LocalDate start = parseDate(fact.get("valid_from"));
        LocalDate end = parseDate(fact.get("valid_to"));
        return (start == null || !start.isAfter(today)) && (end == null || !end.isBefore(today));
    }

    private static Instant parseInstant(Object value) {
        try { return value == null || value.toString().isBlank() ? null : Instant.parse(value.toString()); }
        catch (RuntimeException ignored) { return null; }
    }

    private static LocalDate parseDate(Object value) {
        try { return value == null || value.toString().isBlank() ? null : LocalDate.parse(value.toString()); }
        catch (RuntimeException ignored) { return null; }
    }

    private static String text(Object value) { return value == null ? "" : value.toString(); }

    private static double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }
}
