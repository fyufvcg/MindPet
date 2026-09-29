package service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** Stores durable facts and maintains a deterministic, event-time version chain. */
@Service
public class MemoryFactService {

    private static final java.util.Set<String> AFFIRMATIVE_ASSERTIONS = java.util.Set.of("observed", "confirmed");

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public record FactCandidate(String predicate, String value, String valueJson,
                                String scope, String assertion, double confidence,
                                String validFrom, String validTo, String observedAt,
                                String timezone, String rawTimeExpression,
                                String normalizedStart, String normalizedEnd,
                                String precision, String timeStatus,
                                String sourceTurnId, String rawText, long sourceSequence) {}

    public record SavedFact(long id, boolean inserted, Long supersededId) {}

    private record ExistingFact(long id, String value, String observedAt, String sourceTurnId,
                                double confidence, long sourceSequence) {}

    public MemoryFactService(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemDefaultZone());
    }

    @Autowired
    public MemoryFactService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    @Transactional
    public SavedFact merge(String userId, FactCandidate candidate) {
        if (userId == null || userId.isBlank() || candidate == null) return new SavedFact(0, false, null);
        String predicate = clean(candidate.predicate());
        String value = clean(candidate.value());
        String scope = clean(candidate.scope()).toLowerCase(java.util.Locale.ROOT);
        String assertion = clean(candidate.assertion()).toLowerCase(java.util.Locale.ROOT);
        String sourceTurn = clean(candidate.sourceTurnId());
        double confidence = candidate.confidence();
        if (!MemoryFactOntology.supports(predicate, scope, assertion)
                || value.isBlank() || value.length() > 500
                || !Double.isFinite(confidence) || confidence < 0.55 || confidence > 1.0
                || sourceTurn.isBlank()
                || MemoryContentSafety.looksSensitive(value)
                || MemoryContentSafety.looksSensitive(candidate.valueJson())
                || MemoryContentSafety.looksSensitive(candidate.rawText())) {
            return new SavedFact(0, false, null);
        }

        String normalizedStart = clean(candidate.normalizedStart());
        ExistingFact sameProposal = findByIdempotencyKey(userId, sourceTurn, predicate, value, normalizedStart);
        if (sameProposal != null) return new SavedFact(sameProposal.id(), false, null);

        if (!"negated".equals(assertion)) {
            ExistingFact sameActiveValue = findActiveLogicalFact(userId, predicate, value, scope, normalizedStart);
            if (sameActiveValue != null) return new SavedFact(sameActiveValue.id(), false, null);
        }

        String status = statusFor(assertion);
        Long supersedesId = null;
        if ("negated".equals(assertion)) {
            deactivateNegatedValue(userId, candidate, predicate, value, scope);
        } else if (maySupersede(predicate, scope, assertion, confidence, candidate)) {
            List<ExistingFact> active = activeFactsForSlot(userId, predicate, scope);
            List<ExistingFact> older = new ArrayList<>();
            boolean newerActiveExists = false;
            for (ExistingFact fact : active) {
                if (value.equals(fact.value())) continue;
                int order = compareVersion(candidate, confidence, value, fact);
                if (order > 0) older.add(fact);
                else if (order < 0) newerActiveExists = true;
            }
            if (newerActiveExists) {
                status = "superseded";
            } else if (!older.isEmpty()) {
                ExistingFact predecessor = older.stream()
                    .max((left, right) -> compareVersion(left, right))
                    .orElse(null);
                if (predecessor != null) supersedesId = predecessor.id();
                for (ExistingFact fact : older) {
                    jdbc.update("UPDATE memory_fact SET status='superseded',updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='active'",
                        fact.id());
                }
            }
        }

        int inserted = jdbc.update("INSERT OR IGNORE INTO memory_fact "
                + "(user_id,predicate,value_text,value_json,scope,assertion,confidence,valid_from,valid_to,observed_at,event_timezone,raw_time_expression,normalized_start,normalized_end,time_precision,time_status,source_turn_id,raw_text,status,supersedes_id,updated_at) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, ?,?,CURRENT_TIMESTAMP)",
            userId, predicate, value, clean(candidate.valueJson()), scope,
            assertion, confidence, emptyToNull(candidate.validFrom()),
            emptyToNull(candidate.validTo()), emptyToNull(candidate.observedAt()), clean(candidate.timezone()),
            clean(candidate.rawTimeExpression()), emptyToNull(candidate.normalizedStart()),
            emptyToNull(candidate.normalizedEnd()), cleanOr(candidate.precision(), "unknown"),
            cleanOr(candidate.timeStatus(), "unresolved"), sourceTurn, clean(candidate.rawText()), status, supersedesId);

        ExistingFact saved = findByIdempotencyKey(userId, sourceTurn, predicate, value, normalizedStart);
        return new SavedFact(saved == null ? 0 : saved.id(), inserted > 0, supersedesId);
    }

    public boolean isCurrentPredicate(String predicate) {
        return MemoryFactOntology.isProfileSlot(predicate);
    }

    public static boolean supportsPredicate(String predicate) {
        return MemoryFactOntology.supportsPredicate(predicate);
    }

    public static String clean(String value) { return value == null ? "" : value.trim(); }

    private ExistingFact findByIdempotencyKey(String userId, String sourceTurn, String predicate,
                                               String value, String normalizedStart) {
        List<ExistingFact> rows = jdbc.query("SELECT mf.id,mf.value_text,mf.observed_at,mf.source_turn_id,mf.confidence,"
                + "COALESCE(ct.sequence,0) FROM memory_fact mf LEFT JOIN curator_turns ct "
                + "ON ct.user_id=mf.user_id AND ct.turn_id=mf.source_turn_id "
                + "WHERE mf.user_id=? AND COALESCE(mf.source_turn_id,'')=? AND mf.predicate=? "
                + "AND mf.value_text=? AND COALESCE(mf.normalized_start,'')=? ORDER BY mf.id LIMIT 1",
            (rs, row) -> new ExistingFact(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getDouble(5), rs.getLong(6)),
            userId, sourceTurn, predicate, value, normalizedStart);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ExistingFact findActiveLogicalFact(String userId, String predicate, String value,
                                               String scope, String normalizedStart) {
        List<ExistingFact> rows = jdbc.query("SELECT mf.id,mf.value_text,mf.observed_at,mf.source_turn_id,mf.confidence,COALESCE(ct.sequence,0) "
                + "FROM memory_fact mf LEFT JOIN curator_turns ct ON ct.user_id=mf.user_id AND ct.turn_id=mf.source_turn_id "
                + "WHERE mf.user_id=? AND mf.predicate=? AND mf.value_text=? AND mf.scope=? "
                + "AND COALESCE(mf.normalized_start,'')=? AND mf.status='active' "
                + "AND mf.assertion IN ('observed','confirmed') ORDER BY mf.id LIMIT 1",
            (rs, row) -> new ExistingFact(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getDouble(5), rs.getLong(6)),
            userId, predicate, value, scope, normalizedStart);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<ExistingFact> activeFactsForSlot(String userId, String predicate, String scope) {
        String slotScope = "home_location".equals(predicate) ? "stable" : "current";
        if (!slotScope.equals(scope)) return List.of();
        return jdbc.query("SELECT mf.id,mf.value_text,mf.observed_at,mf.source_turn_id,mf.confidence,COALESCE(ct.sequence,0) "
                + "FROM memory_fact mf LEFT JOIN curator_turns ct ON ct.user_id=mf.user_id AND ct.turn_id=mf.source_turn_id "
                + "WHERE mf.user_id=? AND mf.predicate=? AND mf.status='active' AND mf.scope=? "
                + "AND mf.assertion IN ('observed','confirmed') ORDER BY mf.id",
            (rs, row) -> new ExistingFact(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getDouble(5), rs.getLong(6)),
            userId, predicate, slotScope);
    }

    private void deactivateNegatedValue(String userId, FactCandidate candidate,
                                        String predicate, String value, String scope) {
        String slotScope = "home_location".equals(predicate) ? "stable" : "current";
        if (!slotScope.equals(scope)) return;
        List<ExistingFact> active = jdbc.query("SELECT mf.id,mf.value_text,mf.observed_at,mf.source_turn_id,mf.confidence,COALESCE(ct.sequence,0) "
                + "FROM memory_fact mf LEFT JOIN curator_turns ct ON ct.user_id=mf.user_id AND ct.turn_id=mf.source_turn_id "
                + "WHERE mf.user_id=? AND mf.predicate=? AND mf.scope=? AND mf.value_text=? "
                + "AND mf.status='active' AND mf.assertion IN ('observed','confirmed') ORDER BY mf.id",
            (rs, row) -> new ExistingFact(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getDouble(5), rs.getLong(6)),
            userId, predicate, scope, value);
        for (ExistingFact prior : active) {
            if (compareVersion(candidate, candidate.confidence(), value, prior) > 0) {
                jdbc.update("UPDATE memory_fact SET status='inactive',updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='active'",
                    prior.id());
            }
        }
    }

    private int compareVersion(FactCandidate candidate, double candidateConfidence,
                               String candidateValue, ExistingFact existing) {
        Instant candidateTime = parseInstant(candidate.observedAt());
        Instant existingTime = parseInstant(existing.observedAt());
        if (candidateTime != null && existingTime != null) {
            int time = candidateTime.compareTo(existingTime);
            if (time != 0) return time;
        }
        if (candidate.sourceSequence() > 0 && existing.sourceSequence() > 0) {
            int sequence = Long.compare(candidate.sourceSequence(), existing.sourceSequence());
            if (sequence != 0) return sequence;
        }
        int confidence = Double.compare(candidateConfidence, existing.confidence());
        if (confidence != 0) return confidence;
        return candidateValue.compareTo(existing.value());
    }

    private int compareVersion(ExistingFact left, ExistingFact right) {
        Instant leftTime = parseInstant(left.observedAt());
        Instant rightTime = parseInstant(right.observedAt());
        if (leftTime != null && rightTime != null) {
            int time = leftTime.compareTo(rightTime);
            if (time != 0) return time;
        }
        int sequence = Long.compare(left.sourceSequence(), right.sourceSequence());
        if (sequence != 0) return sequence;
        int confidence = Double.compare(left.confidence(), right.confidence());
        if (confidence != 0) return confidence;
        int value = left.value().compareTo(right.value());
        return value != 0 ? value : Long.compare(left.id(), right.id());
    }

    private boolean maySupersede(String predicate, String scope, String assertion,
                                 double confidence, FactCandidate candidate) {
        if (confidence < 0.85 || !AFFIRMATIVE_ASSERTIONS.contains(assertion)) return false;
        if (!MemoryFactOntology.isProjectable(predicate, scope, assertion)) return false;

        String timeStatus = clean(candidate.timeStatus()).toLowerCase(java.util.Locale.ROOT);
        String rawTime = clean(candidate.rawTimeExpression());
        if ("ambiguous".equals(timeStatus) || "unresolved".equals(timeStatus) && !rawTime.isBlank()) return false;

        ZoneId zone;
        try { zone = ZoneId.of(cleanOr(candidate.timezone(), clock.getZone().getId())); }
        catch (RuntimeException ignored) { zone = clock.getZone(); }
        LocalDate today = LocalDate.now(clock.withZone(zone));
        LocalDate start = parseDate(candidate.normalizedStart());
        LocalDate end = parseDate(candidate.normalizedEnd());
        return (start == null || !start.isAfter(today)) && (end == null || !end.isBefore(today));
    }

    private static Instant parseInstant(String value) {
        try { return value == null || value.isBlank() ? null : Instant.parse(value); }
        catch (RuntimeException ignored) { return null; }
    }

    private static String statusFor(String assertion) {
        return switch (assertion) {
            case "negated" -> "inactive";
            case "planned", "possible", "uncertain" -> "proposed";
            default -> "active";
        };
    }

    private static String cleanOr(String value, String fallback) {
        String cleaned = clean(value);
        return cleaned.isBlank() ? fallback : cleaned;
    }

    private static String emptyToNull(String value) {
        String cleaned = clean(value);
        return cleaned.isBlank() ? null : cleaned;
    }

    private static LocalDate parseDate(String value) {
        try { return value == null || value.isBlank() ? null : LocalDate.parse(value); }
        catch (RuntimeException ignored) { return null; }
    }
}
