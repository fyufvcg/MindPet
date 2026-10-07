package service.v3;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;
import static org.assertj.core.api.Assertions.assertThat;

class SensitivePersistenceGuardTest {
    private JdbcTemplate database() {
        SQLiteDataSource source=new SQLiteDataSource();source.setUrl("jdbc:sqlite::memory:");
        // Keep a single connection: memory DB is connection-local.
        try {
            var connection=source.getConnection();
            return new JdbcTemplate(SensitivePersistenceGuard.wrap(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,true)));
        } catch(java.sql.SQLException e){throw new IllegalStateException(e);}
    }
    @Test void secondDefenseCoversEveryDurableTableEvenWithoutAnExtractionDecision() {
        JdbcTemplate jdbc=database();
        for(String table:new String[]{"long_term_memory","memory_fact","user_profile_current","user_insight","kg_entity","kg_entity_alias","kg_relation","kg_fact_event","kg_evidence"}) {
            jdbc.execute("CREATE TABLE "+table+"(user_id TEXT,source_turn_id TEXT,payload TEXT,importance REAL)");
            try(var context=SensitivePersistenceGuard.context("guard_user_91，这个值才是我的合成测试账号")) {
                jdbc.update("INSERT INTO "+table+"(user_id,source_turn_id,payload,importance) VALUES(?,?,?,?)","user-stable","turn-stable","guard_user_91",.8);
            }
            assertThat(jdbc.queryForMap("SELECT * FROM "+table))
                .containsEntry("user_id","user-stable").containsEntry("source_turn_id","turn-stable")
                .containsEntry("payload",SensitivePersistenceGuard.REDACTED).containsEntry("importance",.8);
        }
    }
    @Test void evidenceChecksOwnPayloadAndRetainsTraceability() {
        JdbcTemplate jdbc=database();jdbc.execute("CREATE TABLE kg_evidence(source_turn_id TEXT,user_message TEXT)");
        jdbc.update("INSERT INTO kg_evidence(source_turn_id,user_message) VALUES(?,?)","trace-1","test_9281 是我的测试账号");
        assertThat(jdbc.queryForMap("SELECT * FROM kg_evidence")).containsEntry("source_turn_id","trace-1")
            .containsEntry("user_message","[REDACTED_ACCOUNT] 是我的测试账号");
    }
    @Test void updatesBatchesAndLiteralStatementsCannotBypassTheGuard() throws Exception {
        JdbcTemplate jdbc=database();jdbc.execute("CREATE TABLE long_term_memory(id INTEGER,content TEXT)");
        jdbc.update("INSERT INTO long_term_memory VALUES(1,'safe')");
        jdbc.update("UPDATE long_term_memory SET content=? WHERE id=?","我的测试账号是 demo_user_27",1);
        jdbc.batchUpdate("INSERT INTO long_term_memory(id,content) VALUES(?,?)",java.util.List.of(new Object[]{2,"acct_28 是系统给我的账号 ID"},new Object[]{3,"我的内部账号是 internal_29"}));
        jdbc.execute("INSERT INTO long_term_memory(id,content) VALUES(4,'我的测试账号是 demo_30')");
        assertThat(jdbc.queryForList("SELECT content FROM long_term_memory",String.class))
            .allSatisfy(v->assertThat(v).contains(SensitivePersistenceGuard.REDACTED).doesNotContain("demo_user_27","acct_28","internal_29","demo_30"));
    }
    @Test void splitProfileKeyValueIsProtectedAndScopeDoesNotLeakToNextTurn() {
        JdbcTemplate jdbc=database();jdbc.execute("CREATE TABLE user_profile(user_id TEXT,category TEXT,prop_key TEXT,prop_value TEXT)");
        jdbc.update("INSERT INTO user_profile(user_id,category,prop_key,prop_value) VALUES(?,?,?,?)","user-1","identity","login id","qa_user_73");
        assertThat(jdbc.queryForObject("SELECT prop_value FROM user_profile",String.class)).isEqualTo(SensitivePersistenceGuard.REDACTED);
        jdbc.execute("CREATE TABLE kg_entity(display_name TEXT)");
        try(var scope=SensitivePersistenceGuard.context("我的测试账号是 label_28")) {
            jdbc.update("INSERT INTO kg_entity VALUES(?)","label_28");
        }
        jdbc.update("INSERT INTO kg_entity VALUES(?)","label_28");
        jdbc.update("INSERT INTO kg_entity VALUES(?)","公司公开客服账号是 support");
        assertThat(jdbc.queryForList("SELECT display_name FROM kg_entity",String.class)).containsExactly(SensitivePersistenceGuard.REDACTED,"label_28","公司公开客服账号是 support");
    }
}
