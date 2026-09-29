package service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/** Stores durable facts and maintains the active version chain. */
@Service
public class MemoryFactService {

    private static final Set<String> PREDICATES = Set.of(
        "current_location", "home_location", "occupation_current",
        "relationship_status_current", "current_project", "preference",
        "identity", "experience", "plan", "event");
    private static final Set<String> CURRENT_SLOTS = Set.of(
        "current_location", "occupation_current", "relationship_status_current", "current_project");

    private final JdbcTemplate jdbc;

    public MemoryFactService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record FactCandidate(String predicate, String value, String valueJson,
                                String scope, String assertion, double confidence,
                                String validFrom, String validTo, String observedAt,
                                String timezone, String rawTimeExpression,
                                String normalizedStart, String normalizedEnd,
                                String precision, String timeStatus,
                                String sourceTurnId, String rawText) {}

    public record SavedFact(long id, boolean inserted, Long supersededId) {}

    @Transactional
    public SavedFact merge(String userId, FactCandidate candidate) {
        if (userId == null || userId.isBlank() || candidate == null) return new SavedFact(0, false, null);
        String predicate = clean(candidate.predicate());
        String value = clean(candidate.value());
        if (!PREDICATES.contains(predicate) || value.isBlank()) return new SavedFact(0, false, null);
        double confidence = clamp(candidate.confidence());
        String sourceTurn = clean(candidate.sourceTurnId());
        String normalizedStart = clean(candidate.normalizedStart());
        String existingSql = "SELECT id FROM memory_fact WHERE user_id=? AND predicate=? AND value_text=? "
            + "AND COALESCE(normalized_start,'')=? AND status<>'superseded' LIMIT 1";
        var existing = jdbc.query(existingSql, (rs, row) -> rs.getLong(1),
            userId, predicate, value, normalizedStart);
        if (!existing.isEmpty()) return new SavedFact(existing.get(0), false, null);

        Long superseded = null;
        if (isCurrentPredicate(predicate) && confidence >= 0.85) {
            var active = jdbc.query(
                "SELECT id FROM memory_fact WHERE user_id=? AND predicate=? AND status='active' "
                    + "AND value_text<>? ORDER BY COALESCE(updated_at,created_at) DESC LIMIT 1",
                (rs, row) -> rs.getLong(1), userId, predicate, value);
            if (!active.isEmpty()) {
                superseded = active.get(0);
                jdbc.update("UPDATE memory_fact SET status='superseded',updated_at=CURRENT_TIMESTAMP WHERE id=?", superseded);
            }
        }

        jdbc.update("INSERT OR IGNORE INTO memory_fact "
                + "(user_id,predicate,value_text,value_json,scope,assertion,confidence,valid_from,valid_to,observed_at,event_timezone,raw_time_expression,normalized_start,normalized_end,time_precision,time_status,source_turn_id,raw_text,status,supersedes_id,updated_at) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, 'active',?,CURRENT_TIMESTAMP)",
            userId, predicate, value, clean(candidate.valueJson()), cleanOr(candidate.scope(), "episodic"),
            cleanOr(candidate.assertion(), "observed"), confidence, emptyToNull(candidate.validFrom()),
            emptyToNull(candidate.validTo()), emptyToNull(candidate.observedAt()), clean(candidate.timezone()),
            clean(candidate.rawTimeExpression()), emptyToNull(candidate.normalizedStart()),
            emptyToNull(candidate.normalizedEnd()), cleanOr(candidate.precision(), "unknown"),
            cleanOr(candidate.timeStatus(), "unresolved"), emptyToNull(sourceTurn), clean(candidate.rawText()), superseded);
        Long id = jdbc.queryForObject(existingSql, Long.class, userId, predicate, value, normalizedStart);
        return new SavedFact(id == null ? 0 : id, true, superseded);
    }

    public boolean isCurrentPredicate(String predicate) {
        return CURRENT_SLOTS.contains(predicate) || "home_location".equals(predicate);
    }

    public static String clean(String value) { return value == null ? "" : value.trim(); }

    private static String cleanOr(String value, String fallback) {
        String cleaned = clean(value);
        return cleaned.isBlank() ? fallback : cleaned;
    }

    private static String emptyToNull(String value) {
        String cleaned = clean(value);
        return cleaned.isBlank() ? null : cleaned;
    }

    private static double clamp(double value) {
        return Double.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0.5;
    }
}
