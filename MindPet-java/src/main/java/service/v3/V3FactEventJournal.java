package service.v3;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** Explicitly ended facts are events, not new positive edges in the legacy retrieval schema. */
public final class V3FactEventJournal {
    private static final Pattern CESSATION = Pattern.compile(
        "不再|不是|并非|从未|没有|不用|不使用|不住|已经不|不把|放弃|取消|已经不用|停止|不想.+(?:目标|计划)|"
            + "\\b(?:no longer|do not|does not|don't|doesn't|never|stops?|stopped|gave up|cancelled|canceled|abandoned)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NON_ASSERTED = Pattern.compile(
        "如果|假设|可能|也许|他说|她说|据说|听说|[？?]|\\b(?:if|maybe|might|he said|she said)\\b",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern FUTURE_INTENT = Pattern.compile(
        "准备|打算|将要|未来|以后|计划(?:不|停止|取消|放弃|去)|"
            + "\\b(?:will|would|plan(?:s)? to|intend(?:s)? to|going to)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NEGATIVE_SEMANTIC = Pattern.compile(
        "(?:^|_)(?:DOES_NOT|DO_NOT|NOT_WANT|NO_LONGER|NOT|NEVER|STOP|STOPS|STOPPED|"
            + "CANCEL|CANCELED|CANCELLED|ABANDON|ABANDONED)(?:_|$)", Pattern.CASE_INSENSITIVE);
    private final JdbcTemplate jdbc;

    public V3FactEventJournal(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public static boolean supportsRetraction(String message, String semantic) {
        return negativeSemantic(semantic) && currentNegativeClause(message);
    }

    public static boolean negativeSemantic(String semantic) {
        return semantic != null && NEGATIVE_SEMANTIC.matcher(semantic).find();
    }

    private static boolean currentNegativeClause(String message) {
        if (message == null) return false;
        for (String clause : V3LifecycleScope.clauses(message)) {
            if (CESSATION.matcher(clause).find() && !NON_ASSERTED.matcher(clause).find()
                    && !FUTURE_INTENT.matcher(clause).find() && V3LifecycleScope.effectiveNow(clause)) return true;
        }
        return false;
    }

    /** Scope denial to the proposed endpoint; do not deny another clause's positive fact. */
    public static boolean deniesCurrentEndpoint(String message, String target) {
        if (message == null || target == null || target.isBlank()) return false;
        String endpoint = Pattern.quote(target.trim());
        Pattern before = Pattern.compile(
            "(?:不是|并非|不再|从未|没有|没|不用|不想|不把|不希望|不|停止|取消|放弃)\\s*"
                + "(?:曾经|继续|正在|再)?\\s*(?:使用|用|住在|住|居住在|居住|在|从事|学习|开发|喜欢)?\\s*"
                + endpoint + "|\\b(?:do not|does not|don't|doesn't|not|never|no longer|stopped|gave up|cancelled|canceled|abandoned)\\s+"
                + "(?:use|uses|using|live in|live at|living in|like|work on|plan to)?\\s*" + endpoint,
            Pattern.CASE_INSENSITIVE);
        Pattern after = Pattern.compile(endpoint + "\\s*(?:不再|从未|没有|不用|停止|取消|放弃)"
            + "(?:使用|用|居住|住|学习|开发|了)?", Pattern.CASE_INSENSITIVE);
        for (String clause : V3LifecycleScope.clauses(message)) {
            if (!NON_ASSERTED.matcher(clause).find() && !FUTURE_INTENT.matcher(clause).find()
                    && V3LifecycleScope.effectiveNow(clause)
                    && (before.matcher(clause).find() || after.matcher(clause).find())) return true;
        }
        return false;
    }

    /** Provenance for a lifecycle update, not a newly asserted positive triple. */
    public void recordResolution(String userId, String sessionId, String turnHash, String message,
            String assistant, Instant at, String priorId, String status, double confidence, double importance) {
        jdbc.update("INSERT INTO kg_fact_event(id,user_id,turn_hash,session_id,source_entity_id,target_entity_id,"
                + "relation_id,semantic_predicate,normalized_predicate,polarity,event_kind,occurred_at,"
                + "confidence,importance,user_message,assistant_message,prior_predicate,prior_semantic_predicate) "
                + "SELECT ?,?,?,?,source_entity_id,target_entity_id,id,?,predicate,'negative','RESOLUTION',"
                + "?,?,?,?,?,predicate,semantic_predicate FROM kg_relation WHERE id=? AND user_id=? "
                + "ON CONFLICT(user_id,turn_hash,source_entity_id,target_entity_id,semantic_predicate) DO NOTHING",
            UUID.randomUUID().toString(),userId,turnHash,sessionId,status,at.toString(),confidence,
            importance,message,assistant==null ? "" : assistant,priorId,userId);
    }

    /** Endpoint grounding uses only already persisted, uniquely named identities in this user's DB. */
    public boolean retract(String userId, String sessionId, String turnHash, String message, String assistant,
            Instant at, String source, String target, String predicate, String semantic,
            double confidence, double importance) {
        if (!supportsRetraction(message, semantic) || confidence < .6) return false;
        List<String> sourceIds = endpoint(userId, source, message);
        List<String> targetIds = endpoint(userId, target, message);
        if (sourceIds.size() != 1 || targetIds.size() != 1 || sourceIds.get(0).equals(targetIds.get(0))) return false;
        List<String> prior = jdbc.query(
            "SELECT id FROM kg_relation WHERE user_id=? AND source_entity_id=? AND target_entity_id=? "
                + "AND predicate=? AND fact_status='ACTIVE'",
            (rs, row) -> rs.getString(1), userId, sourceIds.get(0), targetIds.get(0), predicate);
        if (prior.size() != 1) return false; // Never manufacture a former goal/use from a bare denial.
        var old = jdbc.queryForMap("SELECT semantic_predicate FROM kg_relation WHERE id=?",prior.get(0));
        if (!V3LifecycleScope.ceases(message,target,predicate,old.get("semantic_predicate").toString())) return false;
        String eventId = UUID.randomUUID().toString();
        int inserted = jdbc.update(
            "INSERT INTO kg_fact_event(id,user_id,turn_hash,session_id,source_entity_id,target_entity_id,"
                + "relation_id,semantic_predicate,normalized_predicate,polarity,event_kind,occurred_at,"
                + "confidence,importance,user_message,assistant_message,prior_predicate,prior_semantic_predicate) "
                + "SELECT ?,?,?,?,?,?,id,?,?,'negative','RETRACTION',?,?,?,?,?,predicate,semantic_predicate "
                + "FROM kg_relation WHERE id=? "
                + "ON CONFLICT(user_id,turn_hash,source_entity_id,target_entity_id,semantic_predicate) DO NOTHING",
            eventId, userId, turnHash, sessionId, sourceIds.get(0), targetIds.get(0), semantic, predicate,
            at.toString(), confidence, importance, message, assistant == null ? "" : assistant, prior.get(0));
        if (inserted == 0) return true;
        // Keep the original fact and its evidence archived in the event. The existing retrieval
        // algorithm now sees an ended experience, not a still-current plan/use. No new positive edge.
        Integer conflict = jdbc.queryForObject(
            "SELECT COUNT(*) FROM kg_relation WHERE user_id=? AND source_entity_id=? AND target_entity_id=? "
                + "AND predicate='experienced' AND id<>?", Integer.class,
            userId, sourceIds.get(0), targetIds.get(0), prior.get(0));
        jdbc.update("UPDATE kg_relation SET fact_status='SUPERSEDED',resolution_kind='SUPERSEDES',"
                + "temporal_status='BOUNDED',valid_to=?,predicate=? WHERE id=?",
            at.toString(), conflict != null && conflict > 0 ? predicate : "experienced", prior.get(0));
        jdbc.update("INSERT INTO kg_evidence(user_id,turn_hash,relation_id,session_id,user_message,assistant_message) "
                + "VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING",
            userId, turnHash, prior.get(0), sessionId, message, assistant == null ? "" : assistant);
        return true;
    }

    private List<String> endpoint(String userId, String name, String currentMessage) {
        String normalized = name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (normalized.equals("user")) {
            if (!Pattern.compile("我|\\b(?:I|my|me)\\b", Pattern.CASE_INSENSITIVE).matcher(currentMessage).find()) return List.of();
        } else if (!currentMessage.toLowerCase(Locale.ROOT).contains(normalized)) return List.of();
        return jdbc.query("SELECT DISTINCT e.id FROM kg_entity e LEFT JOIN kg_entity_alias a "
                + "ON e.user_id=a.user_id AND e.id=a.entity_id WHERE e.user_id=? "
                + "AND (e.normalized_name=? OR a.normalized_alias=?) LIMIT 2",
            (rs, row) -> rs.getString(1), userId, normalized, normalized);
    }
}
