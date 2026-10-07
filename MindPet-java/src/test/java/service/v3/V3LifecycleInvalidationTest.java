package service.v3;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.sqlite.SQLiteDataSource;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class V3LifecycleInvalidationTest {
    @TempDir Path temp;
    private JdbcTemplate db(String name) {
        var ds=new SQLiteDataSource();ds.setUrl("jdbc:sqlite:"+temp.resolve(name+".sqlite"));
        new ResourceDatabasePopulator(new ClassPathResource("db/sqlite-schema.sql")).execute(ds);
        var j=new JdbcTemplate(ds);
        j.update("INSERT INTO kg_entity(id,user_id,normalized_name,display_name,entity_type,summary) VALUES('subject','u','user','User','person','Current user')");
        return j;
    }
    private void fact(JdbcTemplate j,String id,String target,String predicate,String semantic,String temporal,boolean current) {
        j.update("INSERT OR IGNORE INTO kg_entity(id,user_id,normalized_name,display_name,entity_type) VALUES(?,?,?,?,?)",target,"u",target.toLowerCase(),target,"other");
        j.update("INSERT INTO kg_relation(id,user_id,source_entity_id,target_entity_id,predicate,semantic_predicate,temporal_status,importance,confidence) VALUES(?,'u','subject',?,?,?,?,0.73,0.91)",id,target,predicate,semantic,temporal);
        j.update("INSERT INTO kg_evidence(user_id,turn_hash,relation_id,session_id,user_message) VALUES('u',?,?, 's','seed')",current?"current":"prior",id);
    }
    private int apply(JdbcTemplate j,String message) {return V3LifecycleInvalidation.apply(j,"u","s","current",message,"",Instant.parse("2026-10-06T00:00:00Z"));}
    private String status(JdbcTemplate j,String id) {return j.queryForObject("SELECT fact_status FROM kg_relation WHERE id=?",String.class,id);}

    @Test void explicitSwitchWithoutModelResolutionFlagRetiresOnlyThePriorSlot() {
        var j=db("switch");fact(j,"old","DraftKit","uses","USES","CURRENT",false);
        fact(j,"new","ComposeKit","uses","USES","CURRENT",true);
        assertThat(apply(j,"我以前使用DraftKit，现在改用ComposeKit。")).isEqualTo(1);
        assertThat(status(j,"old")).isEqualTo("SUPERSEDED");assertThat(status(j,"new")).isEqualTo("ACTIVE");
        assertThat(j.queryForMap("SELECT importance,superseded_by,temporal_status FROM kg_relation WHERE id='old'"))
            .containsEntry("importance",.73).containsEntry("superseded_by","new").containsEntry("temporal_status","ENDED");
        assertThat(j.queryForObject("SELECT user_message FROM kg_fact_event",String.class)).contains("改用");
        assertThat(apply(j,"我以前使用DraftKit，现在改用ComposeKit。")).isZero();
    }
    @Test void futureIntentAndPastNegationLeavePresentUseAlone() {
        for(String message:List.of("我以后准备不用DraftKit。","我以前不用DraftKit，现在开始用了。","如果以后换成ComposeKit，我可能不用DraftKit。")) {
            var j=db(UUID.randomUUID().toString());fact(j,"old","DraftKit","uses","USES","CURRENT",false);
            apply(j,message);assertThat(status(j,"old")).isEqualTo("ACTIVE");
        }
    }
    @Test void directCessationWorksEvenWithNoNewExtractedRelations() {
        var j=db("cessation");fact(j,"old","DraftKit","uses","USES","CURRENT",false);
        assertThat(apply(j,"我现在不再使用DraftKit。")).isEqualTo(1);
        assertThat(status(j,"old")).isEqualTo("ENDED");
        assertThat(j.queryForObject("SELECT COUNT(*) FROM kg_evidence",Integer.class)).isEqualTo(1);
    }
    @Test void dislikeIsRetainedAndOnlyContradictoryPositivePreferenceEnds() {
        var j=db("preference");fact(j,"like","麦香饮品","prefers","PREFERS","PERMANENT",false);
        fact(j,"dislike","麦香饮品","dislikes","DISLIKES","PERMANENT",true);
        apply(j,"我不喜欢麦香饮品。");assertThat(status(j,"like")).isEqualTo("ENDED");
        assertThat(status(j,"dislike")).isEqualTo("ACTIVE");
    }
    @Test void homeAndTemporaryCurrentAndFutureDestinationAreDifferentSlotsOrTimes() {
        var j=db("locations");fact(j,"home","青砂城","related_to","HOME_LOCATION","PERMANENT",false);
        fact(j,"current","松泉城","related_to","LIVES_AT","TEMPORARY",false);
        fact(j,"future","石湾城","plans","WILL_MOVE_TO","FUTURE",true);
        apply(j,"我的家在青砂城，最近临时住松泉城，之后准备搬去石湾城。");
        for(var id:List.of("home","current","future"))assertThat(status(j,id)).isEqualTo("ACTIVE");
    }
    @Test void anotherSubjectsSameTargetAndUnrelatedUserAreNotInvalidated() {
        var j=db("subjects");fact(j,"own","DraftKit","uses","USES","CURRENT",false);
        j.update("INSERT INTO kg_entity(id,user_id,normalized_name,display_name,entity_type) VALUES('other','u','colleague','Colleague','person')");
        j.update("INSERT INTO kg_relation(id,user_id,source_entity_id,target_entity_id,predicate,semantic_predicate,temporal_status) VALUES('theirs','u','other','DraftKit','uses','USES','CURRENT')");
        apply(j,"我现在不再使用DraftKit。");assertThat(status(j,"own")).isEqualTo("ENDED");assertThat(status(j,"theirs")).isEqualTo("ACTIVE");
    }

    @Test void completedInstrumentTransitionAndAnaphoricContinuationAreScoped() {
        for(String message:List.of("以前主要用DraftKit，现在已经换成ComposeKit，旧的不用了。",
                "工具换过了：从DraftKit改成ComposeKit，现在用后者。")) {
            var j=db(UUID.randomUUID().toString());fact(j,"old","DraftKit","uses","USES","CURRENT",false);
            fact(j,"new","ComposeKit","uses","USES","CURRENT",true);
            fact(j,"parallel","DraftKit","learns","LEARNS","CURRENT",false);
            apply(j,message);assertThat(status(j,"old")).isEqualTo("SUPERSEDED");
            assertThat(status(j,"new")).isEqualTo("ACTIVE");assertThat(status(j,"parallel")).isEqualTo("ACTIVE");
        }
    }

    @Test void preferencePolarityOnlyRetiresSameObjectPreferenceAndNotOtherFacts() {
        var j=db("preferenceSlots");fact(j,"old","麦香饮品","dislikes","DISLIKES","PERMANENT",false);
        fact(j,"new","麦香饮品","prefers","PREFERS","PERMANENT",true);
        for(String predicate:List.of("uses","learns","works_on","experienced"))
            fact(j,predicate,"麦香饮品",predicate,predicate.toUpperCase(),"CURRENT",false);
        apply(j,"我喜欢麦香饮品。");assertThat(status(j,"old")).isEqualTo("ENDED");
        assertThat(status(j,"new")).isEqualTo("ACTIVE");
        for(String predicate:List.of("uses","learns","works_on","experienced"))assertThat(status(j,predicate)).isEqualTo("ACTIVE");
    }

    @Test void knownExposedSnapshotsAreForkedAndNeverModified() throws Exception {
        String input=System.getProperty("v31.known44");if(input==null)return;
        var mapper=new ObjectMapper();var results=new ArrayList<Map<String,Object>>();
        for(String line:Files.readAllLines(Path.of(input))) {
            JsonNode row=mapper.readTree(line);String cid=row.path("case_id").asText();
            Path original=Path.of(row.path("persisted_facts").get(0).path("snapshot").asText());
            Path fork=temp.resolve(cid+".sqlite");Files.copy(original,fork);
            var ds=new SQLiteDataSource();ds.setUrl("jdbc:sqlite:"+fork);var j=new JdbcTemplate(ds);
            var current=j.queryForList("SELECT * FROM kg_turn_ingest WHERE user_id='e2e_memory_eval_user' ORDER BY created_at DESC");
            // Select the exact current message's retained source, not creation-time ties.
            String message=row.path("user_message").asText();
            var evidence=j.queryForList("SELECT turn_hash,session_id FROM kg_evidence WHERE user_id='e2e_memory_eval_user' AND user_message=? LIMIT 1",message);
            assertThat(evidence).hasSize(1);
            var e=evidence.get(0);
            int changed=V3LifecycleInvalidation.apply(j,"e2e_memory_eval_user",e.get("session_id").toString(),e.get("turn_hash").toString(),message,"",Instant.parse("2026-10-06T00:00:00Z"));
            boolean repaired=true;
            for(JsonNode stale:row.path("true_current_contradictions")) {
                var state=j.queryForMap("SELECT fact_status,temporal_status FROM kg_relation WHERE id=?",stale.path("id").asText());
                repaired &= !state.get("fact_status").equals("ACTIVE");
            }
            results.add(Map.of("case_id",cid,"repaired",repaired,"changed",changed,"role","EXPOSED_PERSISTENCE_REGRESSION_NOT_GENERALIZATION"));
        }
        String output=System.getProperty("v31.known44.output");
        if(output!=null)Files.writeString(Path.of(output),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(results));
        // Report all failures rather than selectively repairing the exposed cases.
        assertThat(results.stream().filter(r->Boolean.TRUE.equals(r.get("repaired"))).count()).isEqualTo(44);
    }
}
