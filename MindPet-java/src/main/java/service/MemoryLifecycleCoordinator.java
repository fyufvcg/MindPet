package service;

import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Additive source handoff. Existing merge, ranking and forgetting policies remain the decision owners. */
public final class MemoryLifecycleCoordinator {
    private final JdbcTemplate jdbc;
    private final MemoryRetrievalPolicy time;

    public MemoryLifecycleCoordinator(JdbcTemplate jdbc, MemoryRetrievalPolicy time) {
        this.jdbc=jdbc; this.time=time;
        jdbc.execute("CREATE TABLE IF NOT EXISTS memory_lifecycle (unit_id TEXT PRIMARY KEY,user_id TEXT NOT NULL,"
            +"importance REAL NOT NULL DEFAULT 0.5,first_recorded_at TEXT,last_confirmed_at TEXT,last_used_at TEXT,"
            +"use_count INTEGER NOT NULL DEFAULT 0,source_count INTEGER NOT NULL DEFAULT 0,pinned INTEGER NOT NULL DEFAULT 0,"
            +"state TEXT NOT NULL DEFAULT 'live',original_scope TEXT,original_status TEXT,reason TEXT NOT NULL DEFAULT '',"
            +"retired_at TEXT,updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_memory_lifecycle_user ON memory_lifecycle(user_id,state)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS memory_lifecycle_use (id TEXT PRIMARY KEY,user_id TEXT NOT NULL,"
            +"unit_id TEXT NOT NULL,used_at TEXT NOT NULL)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_memory_lifecycle_use_unit ON memory_lifecycle_use(unit_id,used_at)");
    }

    /** Read all linked sources, including originals hidden by compaction; never use merge time as age. */
    public void synchronize(String userId) {
        for(Map<String,Object> unit:jdbc.queryForList("SELECT u.id,u.scope,u.status,u.content,u.unit_type,u.fact_id,"
            +"f.predicate,f.value_text,f.assertion,f.raw_text,f.observed_at,f.last_observed_at,f.confidence "
            +"FROM memory_retrieval_unit u LEFT JOIN memory_fact f ON f.id=u.fact_id AND f.user_id=u.user_id "
            +"WHERE u.user_id=? AND u.unit_type IN ('fact','insight','growth','residual_memory') "
            +"AND u.status NOT IN ('rolled_back','merged_duplicate','stale') ORDER BY u.id",userId)) {
            String id=text(unit.get("id"));
            List<Map<String,Object>> quotes=jdbc.queryForList("SELECT DISTINCT s.source_turn_id,s.evidence_text,ct.occurred_at "
                +"FROM memory_retrieval_source s LEFT JOIN curator_turns ct ON ct.user_id=? AND ct.turn_id=s.source_turn_id "
                +"WHERE s.unit_id=? AND s.source_turn_id IS NOT NULL",userId,id);
            List<Map<String,Object>> sources=jdbc.queryForList("SELECT DISTINCT m.id,m.content,m.importance,m.created_at "
                +"FROM long_term_memory m WHERE m.user_id=? AND (EXISTS(SELECT 1 FROM memory_retrieval_source s "
                +"WHERE s.unit_id=? AND s.source_type='long_term_memory' AND s.source_id=CAST(m.id AS TEXT)) "
                +"OR EXISTS(SELECT 1 FROM memory_retrieval_source s JOIN curator_turns ct ON ct.user_id=m.user_id "
                +"AND ct.turn_id=s.source_turn_id WHERE s.unit_id=? AND ct.session_id=m.session_id AND ct.user_message=m.content))",
                userId,id,id);
            String first="",confirmed=""; double importance=0.5; boolean found=false,pinned=false;
            for(Map<String,Object> source:sources) {
                double v=decimal(source.get("importance"));
                if(Double.isFinite(v)) {importance=found?Math.max(importance,v):v;found=true;}
                first=earlier(first,text(source.get("created_at")));
                pinned|=text(source.get("content")).matches("(?s).*(?:请记住|请长期记住|一定要记住|不要忘记|长期保留).*" )
                    && !MemoryContentSafety.looksSensitive(text(source.get("content")));
            }
            for(Map<String,Object> quote:quotes) {
                String evidence=text(quote.get("evidence_text"));
                if(evidence.isBlank())continue;
                if("fact".equals(text(unit.get("unit_type"))) && !MemoryCuratorFactSupport.supports(evidence,
                    new MemoryEvidenceCoverage.Evidence(text(unit.get("predicate")),text(unit.get("value_text")),
                        text(unit.get("scope")),text(unit.get("assertion")),evidence))) continue;
                confirmed=later(confirmed,text(quote.get("occurred_at")));
                pinned|=evidence.matches("(?s).*(?:请记住|请长期记住|一定要记住|不要忘记|长期保留).*" )
                    && !MemoryContentSafety.looksSensitive(evidence);
            }
            // Existing fact confirmation time is source time, not updated_at or created_at.
            if(Set.of("observed","confirmed","reported").contains(text(unit.get("assertion")))
                    || "plan".equals(text(unit.get("predicate")))) {
                confirmed=later(confirmed,text(unit.get("last_observed_at")));
                confirmed=later(confirmed,text(unit.get("observed_at")));
            }
            if(first.isBlank())first=confirmed;
            List<Map<String,Object>> previous=jdbc.queryForList("SELECT * FROM memory_lifecycle WHERE unit_id=?",id);
            String state=previous.isEmpty()?"live":text(previous.get(0).get("state"));
            if(!previous.isEmpty() && "forgotten".equals(state)
                    && after(confirmed,text(previous.get(0).get("last_confirmed_at")))) {
                restore(userId,id,"new_source_confirmation");state="live";
            }
            jdbc.update("INSERT INTO memory_lifecycle(unit_id,user_id,importance,first_recorded_at,last_confirmed_at,source_count,pinned,original_scope,original_status) "
                +"VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(unit_id) DO UPDATE SET importance=excluded.importance,"
                +"first_recorded_at=excluded.first_recorded_at,last_confirmed_at=excluded.last_confirmed_at,source_count=excluded.source_count,"
                +"pinned=MAX(memory_lifecycle.pinned,excluded.pinned),updated_at=CURRENT_TIMESTAMP",
                id,userId,Math.max(0,Math.min(1,importance)),empty(first),empty(confirmed),sources.size(),pinned?1:0,unit.get("scope"),unit.get("status"));
            jdbc.update("UPDATE memory_lifecycle SET use_count=(SELECT COUNT(*) FROM memory_lifecycle_use x WHERE x.unit_id=?),"
                +"last_used_at=(SELECT MAX(used_at) FROM memory_lifecycle_use x WHERE x.unit_id=?) WHERE unit_id=?",id,id,id);
            if("forgotten".equals(state)) jdbc.update("UPDATE memory_retrieval_unit SET searchable=0,status='inactive' WHERE user_id=? AND id=?",userId,id);
            if("archived".equals(state)) jdbc.update("UPDATE memory_retrieval_unit SET searchable=1,scope='historical',status='historical' WHERE user_id=? AND id=?",userId,id);
        }
    }

    /** Only final selected derived units count as use; one retrieval cannot reinforce siblings. */
    public void recordUse(String userId,List<String> unitIds) {
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()))
            .executeWithoutResult(status -> recordUseAtomically(userId,unitIds));
    }

    private void recordUseAtomically(String userId,List<String> unitIds) {
        String event=UUID.randomUUID().toString(),stamp=Instant.ofEpochMilli(time.nowMillis()).toString();
        if(jdbc.queryForObject("SELECT COUNT(*) FROM memory_lifecycle WHERE user_id=?",Integer.class,userId)==0)synchronize(userId);
        for(String id:new LinkedHashSet<>(unitIds)) {
            int eligible=jdbc.queryForObject("SELECT COUNT(*) FROM memory_retrieval_unit WHERE user_id=? AND id=? "
                +"AND searchable=1 AND unit_type IN ('fact','insight','growth','residual_memory')",Integer.class,userId,id);
            if(eligible==0)continue;
            jdbc.update("INSERT OR IGNORE INTO memory_lifecycle_use(id,user_id,unit_id,used_at) VALUES(?,?,?,?)",event+"|"+id,userId,id,stamp);
            jdbc.update("UPDATE memory_lifecycle SET use_count=(SELECT COUNT(*) FROM memory_lifecycle_use WHERE unit_id=?),"
                +"last_used_at=? WHERE user_id=? AND unit_id=?",id,stamp,userId,id);
        }
    }

    /** Apply the unchanged forgetting policy to the derived representation and its source metadata. */
    public int evaluate(String userId) {
        synchronize(userId); int retired=0;
        for(Map<String,Object> row:jdbc.queryForList("SELECT l.*,u.content,u.scope,u.status,u.fact_id,f.assertion,f.raw_text,f.confidence,"
            +"f.valid_to,f.time_status "
            +"FROM memory_lifecycle l JOIN memory_retrieval_unit u ON u.id=l.unit_id AND u.user_id=l.user_id "
            +"LEFT JOIN memory_fact f ON f.id=u.fact_id AND f.user_id=u.user_id WHERE l.user_id=? AND l.state='live' "
            +"AND u.searchable=1 AND u.status='active'",userId)) {
            String content=text(row.get("raw_text"));if(content.isBlank())content=text(row.get("content"));
            String scope=text(row.get("scope")),assertion=text(row.get("assertion"));
            // A resolved validity end is distinct from the event's start/date. Feed the old expiry policy
            // its existing explicit-end form, without changing that policy or treating event age as expiry.
            if("resolved".equals(text(row.get("time_status"))) && text(row.get("valid_to")).matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")
                    && !Set.of("historical","stable").contains(scope)
                    && !MemoryCuratorFactSupport.hasActivityStatusToPreserve(content))
                content += "，有效至"+row.get("valid_to");
            String unitId=text(row.get("unit_id"));
            if((int)decimal(row.get("source_count"))==0 || jdbc.queryForObject(
                    "SELECT COUNT(*) FROM kg_evidence g WHERE g.user_id=? AND EXISTS(SELECT 1 FROM memory_retrieval_source s "
                    +"WHERE s.unit_id=? AND s.source_turn_id=g.turn_hash)",Integer.class,userId,unitId)>0)continue;
            boolean protectedMemory=decimal(row.get("pinned"))>0 || decimal(row.get("importance"))>=0.6
                || Set.of("stable","current","historical").contains(scope) || "negated".equals(assertion)
                || MemoryCuratorFactSupport.hasActivityStatusToPreserve(content);
            String recent=later(text(row.get("last_confirmed_at")),text(row.get("last_used_at")));
            double confidence=row.get("confidence")==null?1:decimal(row.get("confidence"));
            var decision=MemoryForgettingPolicy.decide(content,decimal(row.get("importance")),confidence,
                (int)decimal(row.get("use_count")),MemoryLayer.fromImportance(decimal(row.get("importance"))).getLevel(),
                time.readStorageTimestamp(recent),time.readStorageTimestamp(text(row.get("first_recorded_at"))),time);
            if(!decision.retire())continue;
            String id=text(row.get("unit_id"));boolean archive="explicit_validity_expired".equals(decision.reason());
            // Expired arrangements remain historical evidence even when important; protection bars discarding.
            if(protectedMemory&&!archive)continue;
            jdbc.update("UPDATE memory_lifecycle SET state=?,original_scope=?,original_status=?,reason=?,retired_at=?,updated_at=CURRENT_TIMESTAMP "
                +"WHERE user_id=? AND unit_id=?",archive?"archived":"forgotten",scope,row.get("status"),decision.reason(),time.sqlNow(),userId,id);
            jdbc.update("UPDATE memory_retrieval_unit SET searchable=?,status=?,scope=?,updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND id=?",
                archive?1:0,archive?"historical":"inactive",archive?"historical":scope,userId,id);
            if(row.get("fact_id")!=null && archive) new ProfileProjectionService(jdbc,
                java.time.Clock.fixed(Instant.ofEpochMilli(time.nowMillis()),java.time.ZoneOffset.UTC))
                .reconcileSlot(userId, text(jdbc.queryForMap("SELECT predicate FROM memory_fact WHERE id=?",row.get("fact_id")).get("predicate")));
            log(userId,id,archive?"ARCHIVE":"RETIRE",decision.reason());retired++;
        }
        return retired;
    }

    /** Restore the same identity and source chain; never revive cancelled, superseded or rolled-back facts. */
    public boolean restore(String userId,String unitId,String reason) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT l.*,u.fact_id,f.status AS fact_status FROM memory_lifecycle l "
            +"JOIN memory_retrieval_unit u ON u.id=l.unit_id LEFT JOIN memory_fact f ON f.id=u.fact_id "
            +"WHERE l.user_id=? AND l.unit_id=? AND l.state='forgotten'",userId,unitId);
        if(rows.isEmpty())return false;
        Map<String,Object> r=rows.get(0);
        if(r.get("fact_id")!=null && !Set.of("active","proposed").contains(text(r.get("fact_status"))))return false;
        jdbc.update("UPDATE memory_retrieval_unit SET searchable=1,scope=?,status=?,updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND id=?",
            r.get("original_scope"),r.get("original_status"),userId,unitId);
        jdbc.update("UPDATE memory_lifecycle SET state='live',reason=?,retired_at=NULL WHERE user_id=? AND unit_id=?",reason,userId,unitId);
        log(userId,unitId,"RESTORE",reason);return true;
    }

    /** Rebuild only missing vectors using the final text. Missing provider leaves text and source recoverable. */
    public int repairMissingEmbeddings(String userId, java.util.function.Function<String,byte[]> prepare) {
        int repaired=0;
        for(Map<String,Object> row:jdbc.queryForList("SELECT id,content FROM memory_retrieval_unit WHERE user_id=? "
                +"AND searchable=1 AND status IN ('active','historical') AND unit_type='fact' AND embedding IS NULL ORDER BY id LIMIT 40",userId)) {
            String content=text(row.get("content"));
            byte[] vector=prepare.apply(content);
            if(vector==null||vector.length==0||vector.length%Float.BYTES!=0)continue;
            java.nio.ByteBuffer b=java.nio.ByteBuffer.wrap(vector).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            double norm=0;boolean valid=true;
            while(b.remaining()>=Float.BYTES){float v=b.getFloat();valid&=Float.isFinite(v);norm+=v*(double)v;}
            if(!valid||norm<=0)continue;
            repaired+=jdbc.update("UPDATE memory_retrieval_unit SET embedding=? WHERE user_id=? AND id=? AND content=? AND embedding IS NULL",
                vector,userId,row.get("id"),content);
        }
        return repaired;
    }

    private void log(String user,String unit,String action,String reason) {
        jdbc.update("INSERT INTO memory_compaction_log(user_id,batch_sequence,action,unit_id,source_id,tokens_before,tokens_after,reason) "
            +"VALUES(?,0,?,?,?,0,0,?)",user,action,unit,unit,"lifecycle:"+reason);
    }
    private String earlier(String a,String b) {Timestamp x=time.readStorageTimestamp(a),y=time.readStorageTimestamp(b);return y==null?a:x==null||y.before(x)?b:a;}
    private String later(String a,String b) {Timestamp x=time.readStorageTimestamp(a),y=time.readStorageTimestamp(b);return y==null?a:x==null||y.after(x)?b:a;}
    private boolean after(String a,String b) {Timestamp x=time.readStorageTimestamp(a),y=time.readStorageTimestamp(b);return x!=null&&(y==null||x.after(y));}
    private static String text(Object v){return v==null?"":v.toString();}
    private static Object empty(String v){return v.isBlank()?null:v;}
    private static double decimal(Object v){return v instanceof Number n?n.doubleValue():0;}
}
