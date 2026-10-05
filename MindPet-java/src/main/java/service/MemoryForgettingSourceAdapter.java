package service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Writes only forgetting-owned raw eligibility, using the existing historical retrieval contract. */
public final class MemoryForgettingSourceAdapter {
    private final JdbcTemplate jdbc;
    private final MemoryRetrievalPolicy time;
    private final EmbeddingService embedding;

    public MemoryForgettingSourceAdapter(JdbcTemplate jdbc, MemoryRetrievalPolicy time) {
        this(jdbc,time,null);
    }
    public MemoryForgettingSourceAdapter(JdbcTemplate jdbc, MemoryRetrievalPolicy time, EmbeddingService embedding) {
        this.jdbc = jdbc;
        this.time = time;
        this.embedding = embedding;
    }

    private record Source(String id, String session, String content, byte[] embedding, double importance,
                          double confidence, int accessCount, int layer, Timestamp accessed, Timestamp created) {}
    private record Unit(String id, String type, String status, int searchable, String content) {}
    private record Link(String unitId, String type, String sourceId, String turn) {}
    private record Fact(String turn, String raw, String scope, String assertion, String status,
                        String deadline, String timeStatus, long predecessor, boolean hasSuccessor) {}
    private record Context(Map<String, Unit> units, Map<String, List<Link>> bySource,
                           Map<String, List<Link>> byUnit, List<Fact> facts, Map<String, Set<String>> turns,
                           Set<String> graphTurns, Set<String> graphTexts) {}

    public int prune(String userId) {
        if (userId == null || userId.isBlank()) return 0;
        // Raw eligibility, historical projection and audit records must commit together.
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        Integer result = transaction.execute(ignored -> pruneAtomically(userId));
        return result == null ? 0 : result;
    }

    private int pruneAtomically(String userId) {
        List<Source> sources = jdbc.query("SELECT id,session_id,content,embedding,importance,confidence,access_count,"
                + "layer,last_accessed,created_at FROM long_term_memory WHERE user_id=? AND searchable=1 ORDER BY id",
            (rs, row) -> new Source(rs.getString("id"), rs.getString("session_id"), rs.getString("content"),
                rs.getBytes("embedding"), rs.getDouble("importance"), rs.getDouble("confidence"),
                rs.getInt("access_count"), rs.getInt("layer"), time.readStorageTimestamp(rs.getString("last_accessed")),
                time.readStorageTimestamp(rs.getString("created_at"))), userId);
        Context context = loadContext(userId);
        int retired = 0;
        for (Source source : sources) {
            var decision = MemoryForgettingPolicy.decide(source.content(), source.importance(), source.confidence(),
                source.accessCount(), source.layer(), source.accessed(), source.created(), time);
            if (!decision.retire() || protectedByStoredEvidence(source, context)) continue;
            List<Link> links = context.bySource().getOrDefault(source.id(), List.of());
            // Do not change eligibility of a source participating in a derived or shared representation.
            if (!independentRawUnits(source, links, context)) continue;
            boolean archive = "explicit_validity_expired".equals(decision.reason());
            List<String> units = links.stream().map(Link::unitId).distinct().toList();
            Archive archiveValue=archive?archiveValue(source):null;
            if (archive && units.isEmpty()) units = List.of(createHistoryUnit(userId, source, context,archiveValue));
            for (String unit : units) {
                if (archive) {
                    // Active is the representation lifecycle; historical is its factual scope.
                    // The new type prevents raw-source reconciliation from treating extraction as an edit.
                    jdbc.update("UPDATE memory_retrieval_unit SET unit_type='forgetting_archive',status='active',scope='historical',searchable=1,"
                            + "content=?,embedding=?,token_count=?,valid_from=?,valid_to=?,"
                            + "compaction_version=compaction_version+1,updated_at=CURRENT_TIMESTAMP "
                            + "WHERE user_id=? AND id=? AND unit_type='raw_memory' AND status='active' AND searchable=1",
                        archiveValue.content(),archiveValue.embedding(),MemoryTokenCounter.count(archiveValue.content()),
                        archiveValue.from(),archiveValue.to(),userId, unit);
                    jdbc.update("UPDATE memory_retrieval_source SET evidence_text=COALESCE(NULLIF(evidence_text,''),?) "
                        + "WHERE unit_id=? AND source_type='long_term_memory' AND source_id=?",source.content(),unit,source.id());
                } else {
                    jdbc.update("UPDATE memory_retrieval_unit SET status='inactive',searchable=0,"
                            + "compaction_version=compaction_version+1,updated_at=CURRENT_TIMESTAMP "
                            + "WHERE user_id=? AND id=? AND unit_type='raw_memory' AND status='active' AND searchable=1",
                        userId, unit);
                }
            }
            int changed = jdbc.update("UPDATE long_term_memory SET searchable=0 WHERE user_id=? AND id=? AND searchable=1",
                userId, source.id());
            if (changed != 1) throw new IllegalStateException("Source changed during forgetting: " + source.id());
            int tokens = MemoryTokenCounter.count(source.content());
            String reason = "forgetting:" + decision.reason() + (archive ? ":history_preserved" : "");
            jdbc.update("INSERT INTO memory_compaction_log(user_id,batch_sequence,action,unit_id,source_id,"
                    + "tokens_before,tokens_after,reason) VALUES(?,0,'RETIRE',?,?,?,0,?)",
                userId, units.isEmpty() ? "" : units.get(0), source.id(), tokens, reason);
            if (archive) for (String unit : units) jdbc.update(
                "INSERT INTO memory_compaction_log(user_id,batch_sequence,action,unit_id,source_id,tokens_before,tokens_after,reason) "
                    + "VALUES(?,0,'ARCHIVE',?,?,?,?,?)", userId, unit, source.id(), tokens,
                MemoryTokenCounter.count(archiveValue.content()), reason);
            retired++;
        }
        archiveExpiredResiduals(userId, context);
        return retired;
    }

    /** Retire only an independently expressed residual; its mixed raw source stays untouched. */
    private void archiveExpiredResiduals(String userId, Context context) {
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT id,content FROM memory_retrieval_unit WHERE user_id=? AND unit_type='residual_memory' "
                    + "AND status='active' AND searchable=1 AND scope IN ('episodic','planned') "
                    + "AND fact_id IS NULL AND supersedes_unit_id IS NULL ORDER BY id", userId)) {
            String unitId = (String) row.get("id"), content = (String) row.get("content");
            var decision = MemoryForgettingPolicy.decide(content, 1, 1, 0, 3, null, null, time);
            if (!decision.retire() || !"explicit_validity_expired".equals(decision.reason())) continue;
            List<Link> links = context.byUnit().getOrDefault(unitId, List.of());
            Set<String> rawIds = new HashSet<>(), turns = new HashSet<>();
            boolean unsafe = links.isEmpty();
            for (Link link : links) {
                if ("long_term_memory".equals(link.type())) rawIds.add(link.sourceId());
                else if (!"curator_turn".equals(link.type())) unsafe = true;
                if (link.turn() != null && !link.turn().isBlank()) turns.add(link.turn());
            }
            if (unsafe || rawIds.size() != 1 || turns.size() != 1) continue;
            String rawId = rawIds.iterator().next(), turn = turns.iterator().next();
            // Source identity alone cannot establish that this is a complete, independent span.
            List<String> originals = jdbc.query("SELECT content FROM long_term_memory "
                    + "WHERE user_id=? AND CAST(id AS TEXT)=?", (rs, n) -> rs.getString(1), userId, rawId);
            List<String> turnTexts = jdbc.query("SELECT user_message FROM curator_turns WHERE user_id=? AND turn_id=?",
                (rs, n) -> rs.getString(1), userId, turn);
            if (originals.size() != 1 || turnTexts.size() != 1) continue;
            String original = originals.get(0), turnText = turnTexts.get(0);
            if (!completeSuffix(original, content) || !completeSuffix(turnText, content)) continue;
            if (context.graphTurns().contains(turn) || context.graphTexts().contains(content)
                    || context.graphTexts().contains(original) || context.graphTexts().contains(turnText)
                    || context.facts().stream().anyMatch(f -> turn.equals(f.turn()) || content.equals(f.raw()))) continue;
            // Keep the original content and vector paired. Scope routes it through existing history
            // retrieval without rewriting curator facts, shared sources, or their projections.
            var projection = MemoryForgettingArchiveProjection.project(content);
            int changed = jdbc.update("UPDATE memory_retrieval_unit SET scope='historical',valid_from=?,valid_to=?,"
                    + "compaction_version=compaction_version+1,updated_at=CURRENT_TIMESTAMP "
                    + "WHERE user_id=? AND id=? AND unit_type='residual_memory' AND status='active' "
                    + "AND searchable=1 AND scope IN ('episodic','planned')",
                projection.validFrom(), projection.validTo(), userId, unitId);
            if (changed != 1) throw new IllegalStateException("Residual changed during forgetting: " + unitId);
            int tokens = MemoryTokenCounter.count(content);
            jdbc.update("INSERT INTO memory_compaction_log(user_id,batch_sequence,action,unit_id,source_id,"
                    + "tokens_before,tokens_after,reason) VALUES(?,0,'ARCHIVE',?,?,?,?,?)", userId, unitId,
                rawId, tokens, tokens, "forgetting:explicit_validity_expired:residual_history_preserved");
        }
    }

    private static boolean completeSuffix(String source, String residual) {
        if (source == null || residual == null || !source.endsWith(residual)) return false;
        int start = source.length() - residual.length();
        return start == 0 || "。！？!?\\n".indexOf(source.charAt(start - 1)) >= 0;
    }

    private Context loadContext(String userId) {
        Map<String, Unit> units = new HashMap<>();
        jdbc.query("SELECT id,unit_type,status,searchable,content FROM memory_retrieval_unit WHERE user_id=?", rs -> {
            units.put(rs.getString("id"), new Unit(rs.getString("id"), rs.getString("unit_type"),
                rs.getString("status"), rs.getInt("searchable"), rs.getString("content")));
        }, userId);
        Map<String, List<Link>> bySource = new HashMap<>(), byUnit = new HashMap<>();
        jdbc.query("SELECT s.unit_id,s.source_type,s.source_id,s.source_turn_id FROM memory_retrieval_source s "
                + "JOIN memory_retrieval_unit u ON u.id=s.unit_id WHERE u.user_id=?", rs -> {
            Link link = new Link(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4));
            byUnit.computeIfAbsent(link.unitId(), ignored -> new ArrayList<>()).add(link);
            if ("long_term_memory".equals(link.type()))
                bySource.computeIfAbsent(link.sourceId(), ignored -> new ArrayList<>()).add(link);
        }, userId);
        List<Fact> facts = jdbc.query("SELECT f.source_turn_id,f.raw_text,f.scope,f.assertion,f.status,f.valid_to,"
                + "f.time_status,COALESCE(f.supersedes_id,0),EXISTS(SELECT 1 FROM memory_fact next "
                + "WHERE next.user_id=f.user_id AND next.supersedes_id=f.id AND next.status<>'rolled_back') "
                + "FROM memory_fact f WHERE f.user_id=? AND f.status NOT IN ('rolled_back','merged_duplicate')",
            (rs, row) -> new Fact(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getLong(8), rs.getBoolean(9)), userId);
        Map<String, Set<String>> turns = new HashMap<>();
        jdbc.query("SELECT session_id,user_message,turn_id FROM curator_turns WHERE user_id=?", rs -> {
            turns.computeIfAbsent(key(rs.getString(1), rs.getString(2)), ignored -> new HashSet<>()).add(rs.getString(3));
        }, userId);
        Set<String> graphTurns = new HashSet<>(), graphTexts = new HashSet<>();
        jdbc.query("SELECT turn_hash,user_message FROM kg_evidence WHERE user_id=?", rs -> {
            graphTurns.add(rs.getString(1)); graphTexts.add(rs.getString(2));
        }, userId);
        return new Context(units, bySource, byUnit, facts, turns, graphTurns, graphTexts);
    }

    private boolean protectedByStoredEvidence(Source source, Context context) {
        Set<String> turns = new HashSet<>(context.turns().getOrDefault(key(source.session(), source.content()), Set.of()));
        for (Link link : context.bySource().getOrDefault(source.id(), List.of()))
            if (link.turn() != null && !link.turn().isBlank()) turns.add(link.turn());
        if (context.graphTexts().contains(source.content()) || turns.stream().anyMatch(context.graphTurns()::contains)) return true;
        for (Fact fact : context.facts()) {
            // A mapping or exact stored assertion is required; semantic similarity is not source identity.
            if (!turns.contains(fact.turn()) && !source.content().equals(fact.raw())) continue;
            if (Set.of("stable", "current", "historical").contains(fact.scope())
                || "superseded".equals(fact.status()) || "negated".equals(fact.assertion())
                || fact.predecessor() > 0 || fact.hasSuccessor()) return true;
            if (!"resolved".equals(fact.timeStatus())) return true;
            Timestamp deadline = time.readStorageTimestamp(fact.deadline());
            // A structured fact without a trustworthy expired interval may still depend on this source.
            if (deadline == null || deadline.getTime() > time.nowMillis()) return true;
        }
        return false;
    }

    private static boolean independentRawUnits(Source source, List<Link> links, Context context) {
        for (Link link : links) {
            Unit unit = context.units().get(link.unitId());
            if (unit == null || !"raw_memory".equals(unit.type()) || !"active".equals(unit.status())
                || unit.searchable() != 1 || !source.content().equals(unit.content())) return false;
            for (Link sibling : context.byUnit().getOrDefault(unit.id(), List.of())) {
                if ("long_term_memory".equals(sibling.type()) && source.id().equals(sibling.sourceId())) continue;
                if ("curator_turn".equals(sibling.type()) && sibling.sourceId().equals(link.turn())) continue;
                return false;
            }
        }
        return true;
    }

    private record Archive(String content,byte[] embedding,String from,String to) {}
    private Archive archiveValue(Source source) {
        var projection=MemoryForgettingArchiveProjection.project(source.content());
        if(projection.shortened()&&embedding!=null&&source.embedding()!=null) {
            float[] vector=embedding.embed(projection.content());
            if(vector!=null&&vector.length*Float.BYTES==source.embedding().length) {
                boolean valid=true;double norm=0;
                for(float value:vector){valid&=Float.isFinite(value);norm+=value*(double)value;}
                if(valid&&norm>0)return new Archive(projection.content(),VectorSearchService.encode(vector),projection.validFrom(),projection.validTo());
            }
        }
        // Missing or incompatible embeddings preserve full text and its existing vector together.
        return new Archive(source.content(),source.embedding(),null,null);
    }

    private String createHistoryUnit(String userId, Source source, Context context,Archive archive) {
        String id = "forgetting-history-" + UUID.nameUUIDFromBytes((userId + "|" + source.id()).getBytes(StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO memory_retrieval_unit(id,user_id,canonical_key,unit_type,scope,status,searchable,"
                + "content,embedding,token_count,valid_from,valid_to) VALUES(?,?,?,'forgetting_archive','historical','active',1,?,?,?,?,?)",
            id, userId, "forgetting_history|" + source.id(), archive.content(), archive.embedding(),
            MemoryTokenCounter.count(archive.content()),archive.from(),archive.to());
        Set<String> turns = context.turns().getOrDefault(key(source.session(), source.content()), Set.of());
        String turn = turns.size() == 1 ? turns.iterator().next() : null;
        jdbc.update("INSERT INTO memory_retrieval_source(unit_id,source_type,source_id,source_turn_id,evidence_text) "
                + "VALUES(?,'long_term_memory',?,?,?)", id, source.id(), turn, source.content());
        return id;
    }

    private static String key(String session, String content) { return session + "\u0000" + content; }
}
