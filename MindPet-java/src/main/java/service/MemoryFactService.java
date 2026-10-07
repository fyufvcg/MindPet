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
        this.jdbc = service.v3.SensitivePersistenceGuard.protect(jdbc);
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    @Transactional
    public SavedFact merge(String userId, FactCandidate candidate) {
        if (userId == null || userId.isBlank() || candidate == null) return new SavedFact(0, false, null);
        candidate = canonicalCandidate(candidate);
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

        normalizeDirectReports(userId, predicate, scope);
        String normalizedStart = clean(candidate.normalizedStart());
        ExistingFact sameProposal = findByIdempotencyKey(userId, sourceTurn, predicate, value, scope, assertion, normalizedStart);
        if (sameProposal != null && !"rolled_back".equals(jdbc.queryForObject(
                "SELECT status FROM memory_fact WHERE id=?", String.class, sameProposal.id()))) {
            reconcileConfirmedSlot(userId, candidate, sameProposal.id());
            return new SavedFact(sameProposal.id(), false, null);
        }

        ExistingFact sameActiveValue = findActiveLogicalFact(userId, predicate, value, scope, normalizedStart, assertion);
        // A -> B -> A starts a new interval if another slot value was observed between mentions.
        if (sameActiveValue != null && !hasInterveningSlotValue(userId, predicate, scope, candidate, sameActiveValue)) {
            refreshConfirmation(userId, sameActiveValue, candidate);
            reconcileConfirmedSlot(userId, candidate, sameActiveValue.id());
            return new SavedFact(sameActiveValue.id(), false, null);
        }
        if (!"negated".equals(assertion)) advanceProposal(userId, candidate, predicate, value, scope);

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
                    jdbc.update("UPDATE memory_fact SET status='superseded',valid_to=COALESCE(valid_to,?),"
                        + "updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='active'",
                        transitionDate(candidate), fact.id());
                }
            }
        }

        String validFrom = !clean(candidate.validFrom()).isBlank() ? candidate.validFrom()
            : java.util.Set.of("current", "stable").contains(scope) ? transitionDate(candidate) : "";
        if (sameProposal != null) {
            // Reapply a rolled-back row in place, including databases whose legacy NULL key
            // does not collide with SQLite's table-level unique constraint.
            jdbc.update("UPDATE memory_fact SET value_json=?,assertion=?,confidence=?,valid_from=?,valid_to=?,"
                + "observed_at=?,last_observed_at=?,event_timezone=?,raw_time_expression=?,normalized_start=?,normalized_end=?,"
                + "time_precision=?,time_status=?,raw_text=?,status=?,supersedes_id=?,evidence_count=1,updated_at=CURRENT_TIMESTAMP "
                + "WHERE user_id=? AND id=? AND status='rolled_back'", clean(candidate.valueJson()), assertion, confidence,
                emptyToNull(validFrom), emptyToNull(candidate.validTo()), candidate.observedAt(), candidate.observedAt(), candidate.timezone(),
                clean(candidate.rawTimeExpression()), emptyToNull(candidate.normalizedStart()), emptyToNull(candidate.normalizedEnd()),
                cleanOr(candidate.precision(), "unknown"), cleanOr(candidate.timeStatus(), "unresolved"), clean(candidate.rawText()),
                status, supersedesId, userId, sameProposal.id());
            return new SavedFact(sameProposal.id(), true, supersedesId);
        }
        int inserted = jdbc.update("INSERT OR IGNORE INTO memory_fact "
                + "(user_id,predicate,value_text,value_json,scope,assertion,confidence,valid_from,valid_to,observed_at,event_timezone,raw_time_expression,normalized_start,normalized_end,time_precision,time_status,source_turn_id,raw_text,status,supersedes_id,updated_at) "
                + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, ?,?,CURRENT_TIMESTAMP) "
                + "ON CONFLICT DO UPDATE SET status=excluded.status,supersedes_id=excluded.supersedes_id,"
                + "confidence=excluded.confidence,valid_from=excluded.valid_from,valid_to=excluded.valid_to,"
                + "observed_at=excluded.observed_at,last_observed_at=excluded.observed_at,raw_text=excluded.raw_text,"
                + "normalized_end=excluded.normalized_end,time_status=excluded.time_status,evidence_count=1,"
                + "updated_at=CURRENT_TIMESTAMP WHERE memory_fact.status='rolled_back'",
            userId, predicate, value, clean(candidate.valueJson()), scope,
            assertion, confidence, emptyToNull(validFrom),
            emptyToNull(candidate.validTo()), emptyToNull(candidate.observedAt()), clean(candidate.timezone()),
            clean(candidate.rawTimeExpression()), emptyToNull(candidate.normalizedStart()),
            emptyToNull(candidate.normalizedEnd()), cleanOr(candidate.precision(), "unknown"),
            cleanOr(candidate.timeStatus(), "unresolved"), sourceTurn, clean(candidate.rawText()), status, supersedesId);

        ExistingFact saved = findByIdempotencyKey(userId, sourceTurn, predicate, value, scope, assertion, normalizedStart);
        if (saved != null && inserted > 0) jdbc.update("UPDATE memory_fact SET last_observed_at=? WHERE user_id=? AND id=?",
            emptyToNull(candidate.observedAt()), userId, saved.id());
        return new SavedFact(saved == null ? 0 : saved.id(), inserted > 0, supersedesId);
    }

    public boolean isCurrentPredicate(String predicate) {
        return MemoryFactOntology.isProfileSlot(predicate);
    }

    public static boolean supportsPredicate(String predicate) {
        return MemoryFactOntology.supportsPredicate(predicate);
    }

    public static String clean(String value) { return value == null ? "" : value.trim(); }

    private static FactCandidate canonicalCandidate(FactCandidate candidate) {
        String predicate = clean(candidate.predicate());
        String value = MemoryCuratorFactSupport.canonicalValue(predicate, candidate.value());
        String scope = clean(candidate.scope()).toLowerCase(java.util.Locale.ROOT);
        String assertion = clean(candidate.assertion()).toLowerCase(java.util.Locale.ROOT);
        if ("reported".equals(assertion) && MemoryCuratorFactSupport.supports(candidate.rawText(),
                new MemoryEvidenceCoverage.Evidence(predicate, value, scope, assertion, clean(candidate.rawText())))) {
            assertion = MemoryFactOntology.canonicalAssertion(assertion, candidate.rawText());
        }
        return new FactCandidate(candidate.predicate(),
            value, candidate.valueJson(), candidate.scope(), assertion,
            candidate.confidence(), candidate.validFrom(), candidate.validTo(), candidate.observedAt(),
            candidate.timezone(), candidate.rawTimeExpression(), candidate.normalizedStart(), candidate.normalizedEnd(),
            candidate.precision(), candidate.timeStatus(), candidate.sourceTurnId(), candidate.rawText(), candidate.sourceSequence());
    }

    private void normalizeDirectReports(String userId, String predicate, String scope) {
        for (java.util.Map<String, Object> row : jdbc.queryForList(
                "SELECT id,value_text,raw_text,source_turn_id,normalized_start FROM memory_fact WHERE user_id=? AND predicate=? AND scope=? "
                    + "AND assertion='reported' AND status IN ('active','proposed')", userId, predicate, scope)) {
            String raw = clean((String) row.get("raw_text"));
            String value = clean((String) row.get("value_text"));
            if ("observed".equals(MemoryFactOntology.canonicalAssertion("reported", raw))
                    && MemoryCuratorFactSupport.supports(raw,
                        new MemoryEvidenceCoverage.Evidence(predicate, value, scope, "reported", raw))) {
                // Legacy databases may already contain an observed row with the same physical key.
                jdbc.update("UPDATE memory_fact SET assertion='observed',updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND id=? "
                    + "AND NOT EXISTS(SELECT 1 FROM memory_fact other WHERE other.user_id=? AND other.id<>? "
                    + "AND other.source_turn_id=? AND other.predicate=? AND other.value_text=? AND other.scope=? "
                    + "AND other.assertion='observed' AND COALESCE(other.normalized_start,'')=?)",
                    userId, row.get("id"), userId, row.get("id"), row.get("source_turn_id"), predicate, value, scope,
                    clean((String)row.get("normalized_start")));
            }
        }
    }

    private ExistingFact findByIdempotencyKey(String userId, String sourceTurn, String predicate,
                                               String value, String scope, String assertion, String normalizedStart) {
        List<ExistingFact> rows = jdbc.query("SELECT mf.id,mf.value_text,COALESCE(mf.last_observed_at,mf.observed_at),mf.source_turn_id,mf.confidence,"
                + "COALESCE(ct.sequence,0) FROM memory_fact mf LEFT JOIN curator_turns ct "
                + "ON ct.user_id=mf.user_id AND ct.turn_id=mf.source_turn_id "
                + "WHERE mf.user_id=? AND COALESCE(mf.source_turn_id,'')=? AND mf.predicate=? "
                + "AND mf.value_text=? AND mf.scope=? AND (mf.assertion=? "
                + "OR (mf.assertion IN ('observed','confirmed') AND ? IN ('observed','confirmed'))) "
                + "AND COALESCE(mf.normalized_start,'')=? ORDER BY mf.id LIMIT 1",
            (rs, row) -> new ExistingFact(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getDouble(5), rs.getLong(6)),
            userId, sourceTurn, predicate, value, scope, assertion, assertion, normalizedStart);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private ExistingFact findActiveLogicalFact(String userId, String predicate, String value,
                                               String scope, String normalizedStart, String assertion) {
        // Reconfirming an unchanged current/stable value at a later turn must add evidence to
        // the existing fact, not create another active version solely because its mention date changed.
        boolean stateSlot = java.util.Set.of("current", "stable").contains(scope);
        String timeCondition = stateSlot ? "" : "AND COALESCE(mf.normalized_start,'')=? ";
        String sql = "SELECT mf.id,mf.value_text,COALESCE(mf.last_observed_at,mf.observed_at),mf.source_turn_id,mf.confidence,COALESCE(ct.sequence,0) "
                + "FROM memory_fact mf LEFT JOIN curator_turns ct ON ct.user_id=mf.user_id AND ct.turn_id=mf.source_turn_id "
                + "WHERE mf.user_id=? AND mf.predicate=? AND mf.value_text=? AND mf.scope=? "
                + timeCondition + "AND ((mf.status='active' "
                + "AND mf.assertion IN ('observed','confirmed') AND ? IN ('observed','confirmed')) "
                + "OR (mf.status IN ('active','proposed') AND mf.assertion=?) "
                + "OR (mf.status='inactive' AND mf.assertion='negated' AND ?='negated')) ORDER BY mf.id LIMIT 1";
        List<Object> parameters = new ArrayList<>(List.of(userId, predicate, value, scope));
        if (!stateSlot) parameters.add(normalizedStart);
        parameters.add(assertion);
        parameters.add(assertion);
        parameters.add(assertion);
        List<ExistingFact> rows = jdbc.query(sql,
            (rs, row) -> new ExistingFact(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getDouble(5), rs.getLong(6)),
            parameters.toArray());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<ExistingFact> activeFactsForSlot(String userId, String predicate, String scope) {
        String slotScope = "home_location".equals(predicate) ? "stable" : "current";
        if (!slotScope.equals(scope)) return List.of();
        return jdbc.query("SELECT mf.id,mf.value_text,COALESCE(mf.last_observed_at,mf.observed_at),mf.source_turn_id,mf.confidence,COALESCE(ct.sequence,0) "
                + "FROM memory_fact mf LEFT JOIN curator_turns ct ON ct.user_id=mf.user_id AND ct.turn_id=mf.source_turn_id "
                + "WHERE mf.user_id=? AND mf.predicate=? AND mf.status='active' AND mf.scope=? "
                + "AND mf.assertion IN ('observed','confirmed') ORDER BY mf.id",
            (rs, row) -> new ExistingFact(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getDouble(5), rs.getLong(6)),
            userId, predicate, slotScope);
    }

    private void deactivateNegatedValue(String userId, FactCandidate candidate,
                                        String predicate, String value, String scope) {
        List<ExistingFact> active = jdbc.query("SELECT mf.id,mf.value_text,COALESCE(mf.last_observed_at,mf.observed_at),mf.source_turn_id,mf.confidence,COALESCE(ct.sequence,0) "
                + "FROM memory_fact mf LEFT JOIN curator_turns ct ON ct.user_id=mf.user_id AND ct.turn_id=mf.source_turn_id "
                + "WHERE mf.user_id=? AND mf.predicate=? AND mf.scope=? AND mf.value_text=? "
                + "AND mf.status IN ('active','proposed') AND mf.assertion<>'negated' ORDER BY mf.id",
            (rs, row) -> new ExistingFact(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getDouble(5), rs.getLong(6)),
            userId, predicate, scope, value);
        for (ExistingFact prior : active) {
            if (compareVersion(candidate, candidate.confidence(), value, prior) > 0) {
                jdbc.update("UPDATE memory_fact SET status='superseded',valid_to=COALESCE(valid_to,?),"
                    + "updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND id=? AND status IN ('active','proposed')",
                    transitionDate(candidate), userId, prior.id());
            }
        }
    }

    private boolean hasInterveningSlotValue(String userId, String predicate, String scope,
                                             FactCandidate candidate, ExistingFact prior) {
        if ("negated".equals(candidate.assertion())) {
            return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM memory_fact WHERE user_id=? "
                + "AND predicate=? AND scope=? AND value_text=? AND status IN ('active','proposed') AND assertion<>'negated' "
                + "AND COALESCE(last_observed_at,observed_at,'')>?)", Boolean.class, userId, predicate, scope,
                candidate.value(), clean(prior.observedAt())));
        }
        if (!MemoryFactOntology.isProjectable(predicate, scope, candidate.assertion())) return false;
        return activeFactsForSlot(userId, predicate, scope).stream().anyMatch(other ->
            !other.value().equals(prior.value()) && compareVersion(other, prior) > 0);
    }

    private void refreshConfirmation(String userId, ExistingFact prior, FactCandidate candidate) {
        boolean later = compareVersion(candidate, candidate.confidence(), candidate.value(), prior) > 0;
        jdbc.update("UPDATE memory_fact SET confidence=MAX(confidence,?),last_observed_at=CASE WHEN ?=1 THEN ? "
                + "ELSE COALESCE(last_observed_at,observed_at) END,assertion=CASE WHEN ?='confirmed' THEN 'confirmed' "
                + "ELSE assertion END,evidence_count=evidence_count+1,updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND id=?",
            candidate.confidence(), later ? 1 : 0, candidate.observedAt(), candidate.assertion(), userId, prior.id());
    }

    private void reconcileConfirmedSlot(String userId, FactCandidate candidate, long selectedId) {
        if (!maySupersede(candidate.predicate(), candidate.scope(), candidate.assertion(), candidate.confidence(), candidate)) return;
        for (ExistingFact other : activeFactsForSlot(userId, candidate.predicate(), candidate.scope())) {
            if (other.id() != selectedId && compareVersion(candidate, candidate.confidence(), candidate.value(), other) >= 0) {
                jdbc.update("UPDATE memory_fact SET status='superseded',valid_to=COALESCE(valid_to,?),"
                    + "updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND id=?", transitionDate(candidate), userId, other.id());
            }
        }
    }

    private void advanceProposal(String userId, FactCandidate candidate, String predicate, String value, String scope) {
        if (!java.util.Set.of("planned", "observed", "confirmed").contains(candidate.assertion())) return;
        jdbc.update("UPDATE memory_fact SET status='superseded',valid_to=COALESCE(valid_to,?),updated_at=CURRENT_TIMESTAMP "
            + "WHERE user_id=? AND predicate=? AND value_text=? AND scope=? AND assertion='negated' AND status='inactive' "
            + "AND COALESCE(last_observed_at,observed_at,'')<?", transitionDate(candidate), userId, predicate, value, scope,
            clean(candidate.observedAt()));
        // Only advance the same value and time window; independent planned events remain separate.
        jdbc.update("UPDATE memory_fact SET status='superseded',updated_at=CURRENT_TIMESTAMP WHERE user_id=? "
                + "AND predicate=? AND value_text=? AND scope=? AND status='proposed' "
                + "AND assertion IN ('possible','uncertain') AND COALESCE(normalized_start,'')=? "
                + "AND COALESCE(last_observed_at,observed_at,'')<=?",
            userId, predicate, value, scope, clean(candidate.normalizedStart()), clean(candidate.observedAt()));
        if (java.util.Set.of("observed", "confirmed").contains(candidate.assertion()) && "episodic".equals(scope)) {
            jdbc.update("UPDATE memory_fact SET status='superseded',valid_to=COALESCE(valid_to,?),updated_at=CURRENT_TIMESTAMP "
                + "WHERE user_id=? AND predicate=? AND value_text=? AND scope='planned' AND status='proposed' "
                + "AND COALESCE(last_observed_at,observed_at,'')<=?", transitionDate(candidate), userId, predicate, value,
                clean(candidate.observedAt()));
        }
    }

    private static String transitionDate(FactCandidate candidate) {
        if (!clean(candidate.normalizedStart()).isBlank()) return candidate.normalizedStart();
        String observed = clean(candidate.observedAt());
        return observed.length() >= 10 ? observed.substring(0, 10) : null;
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
