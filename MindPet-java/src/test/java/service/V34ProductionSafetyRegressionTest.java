package service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.sqlite.SQLiteDataSource;
import service.v3.SensitivePersistenceGuard;
import service.v3.V3ReferenceResolution;
import util.Logger;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class V34ProductionSafetyRegressionTest {
    @TempDir Path temp;
    private static final Instant NOW = Instant.parse("2026-10-07T10:00:00Z");
    private JdbcTemplate database(String name) {
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + temp.resolve(name + ".db"));
        new ResourceDatabasePopulator(new ClassPathResource("db/sqlite-schema.sql")).execute(source);
        return new JdbcTemplate(source);
    }
    private SqliteMemoryService memory(JdbcTemplate jdbc) {
        EmbeddingService embedding = mock(EmbeddingService.class);
        when(embedding.embed(anyString())).thenReturn(new float[]{0.0f, 1.0f});
        return new SqliteMemoryService(jdbc, embedding, mock(VectorSearchService.class), mock(Logger.class));
    }

    @Test void accountPhrasesCannotLeakThroughLongTermMemoryAndPublicAccountsStayReadable() {
        JdbcTemplate jdbc = database("ltm");
        SqliteMemoryService memory = memory(jdbc);
        for (String text : List.of("我的测试账号是 test_user_381", "test_user_381 是我的测试账号",
                "qa_internal_47，这个值才是我的合成测试账号")) {
            memory.appendTurn("u", "s", text, .8, .9, "neutral", NOW);
        }
        assertThat(jdbc.queryForList("SELECT content FROM long_term_memory", String.class))
            .hasSize(3).allSatisfy(value -> assertThat(value)
                .contains(SensitivePersistenceGuard.REDACTED).doesNotContain("test_user_381", "qa_internal_47"));
        for (String text : List.of("我的 GitHub 用户名是 octocat", "公司公开客服账号是 support")) {
            memory.appendTurn("u", "s", text, .8, .9, "neutral", NOW);
        }
        assertThat(jdbc.queryForList("SELECT content FROM long_term_memory", String.class))
            .contains("我的 GitHub 用户名是 octocat", "公司公开客服账号是 support");
    }

    @Test void rejectedAccountFactsDoNotEnterTheLatestMasterFactOrProfilePipeline() {
        JdbcTemplate jdbc = database("facts");
        MemoryFactService facts = new MemoryFactService(jdbc);
        var candidate = new MemoryFactService.FactCandidate("employer", "test_user_381", "", "current",
            "observed", .95, null, null, NOW.toString(), "UTC", "", "", "",
            "unknown", "unresolved", "trace-1", "我的测试账号是 test_user_381", 1);
        assertThat(facts.merge("u", candidate).id()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memory_fact", Integer.class)).isZero();
        UserProfileService profiles = new UserProfileService(jdbc, new ProfileProjectionService(jdbc));
        profiles.save("u", "identity", "login id", "test_user_381");
        assertThat(jdbc.queryForObject("SELECT prop_value FROM user_profile", String.class))
            .isEqualTo(SensitivePersistenceGuard.REDACTED);
        profiles.save("u", "identity", "public username", "octocat");
        assertThat(jdbc.queryForObject("SELECT prop_value FROM user_profile WHERE prop_key='public username'",
            String.class)).isEqualTo("octocat");
    }

    @Test void realGraphPersistenceRedactsAccountValuesAndKeepsTurnTraceability() throws Exception {
        JdbcTemplate jdbc = database("graph");
        ObjectMapper mapper = new ObjectMapper();
        KnowledgeGraphService graph = new KnowledgeGraphService(jdbc, mock(DynamicChatClientFactory.class),
            mock(EmbeddingService.class), mock(VectorSearchService.class), memory(jdbc),
            mapper, Runnable::run, mock(Logger.class));
        String account = "qa_internal_47";
        String source = account + "，这个值才是我的合成测试账号";
        String response = mapper.writeValueAsString(Map.of(
            "worthRemembering", true,
            "memory", Map.of("shouldRemember", true, "importance", .9, "confidence", .95, "evidence", source),
            "memoryType", "stable",
            "entities", List.of(Map.of("name", account, "type", "tool", "summary", "internal tool",
                "importance", .9, "aliases", List.of(account))),
            "relations", List.of(Map.of("source", "user", "target", account, "predicate", "uses",
                "semanticPredicate", "USES", "confidence", .95, "importance", .9))));
        Method parser = KnowledgeGraphService.class.getDeclaredMethod("parseExtraction",
            String.class, V3ReferenceResolution.Plan.class, String.class);
        parser.setAccessible(true);
        Object extraction = parser.invoke(graph, response, V3ReferenceResolution.Plan.empty(), source);
        Method persist = KnowledgeGraphService.class.getDeclaredMethod("persist", String.class, String.class,
            String.class, String.class, String.class, extraction.getClass(), Instant.class);
        persist.setAccessible(true);
        persist.invoke(graph, "u", "s", "trace-graph", source, "", extraction, NOW);
        for (String table : List.of("kg_entity", "kg_entity_alias", "kg_relation", "kg_fact_event", "kg_evidence")) {
            assertThat(jdbc.queryForList("SELECT * FROM " + table).toString()).doesNotContain(account);
        }
        assertThat(jdbc.queryForObject("SELECT turn_hash FROM kg_turn_ingest", String.class))
            .isEqualTo("trace-graph");
        // Exercise the final evidence boundary even if admission rejects the whole proposal.
        JdbcTemplate guarded = SensitivePersistenceGuard.protect(jdbc);
        guarded.update("INSERT INTO kg_evidence(user_id,turn_hash,session_id,user_message) VALUES(?,?,?,?)",
            "u", "trace-evidence", "s", source);
        assertThat(jdbc.queryForMap("SELECT turn_hash,user_message FROM kg_evidence"))
            .containsEntry("turn_hash", "trace-evidence")
            .containsEntry("user_message", "[REDACTED_ACCOUNT]，这个值才是我的合成测试账号");
    }

    @Test void existingCredentialFiltersAndPublicNegativesRemainIntact() {
        for (String value : List.of("API key: sk-example-private-value123", "password=secret", "验证码 462091",
                "身份证 110105199001011234", "银行卡 6222021234567890123", "我的测试账号是 test_user_381")) {
            assertThat(MemoryContentSafety.looksSensitive(value)).as(value).isTrue();
        }
        for (String value : List.of("我的 GitHub 用户名是 octocat", "公司公开客服账号是 support", "我的游戏昵称是 NightFox")) {
            assertThat(MemoryContentSafety.looksSensitive(value)).as(value).isFalse();
        }
    }

    @Test void theGuardSharesMasterTransactionsAndRedactionSurvivesRollbackBoundaries() {
        JdbcTemplate jdbc = database("transaction");
        JdbcTemplate guarded = SensitivePersistenceGuard.protect(jdbc);
        var transaction = new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        transaction.executeWithoutResult(status -> {
            // A prior write holds the master's transaction connection; the guard must reuse it.
            jdbc.update("INSERT INTO user_profile(user_id,category,prop_key,prop_value) VALUES('u','identity','seed','safe')");
            guarded.update("INSERT INTO long_term_memory(user_id,content,role) VALUES(?,?,'user')", "u", "我的测试账号是 test_user_381");
            assertThat(jdbc.queryForObject("SELECT content FROM long_term_memory", String.class))
                .isEqualTo("我的测试账号是 [REDACTED_ACCOUNT]");
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_profile", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM long_term_memory", Integer.class)).isZero();
        transaction.executeWithoutResult(status -> guarded.update(
            "INSERT INTO long_term_memory(user_id,content,role) VALUES(?,?,'user')", "u", "test_user_381 是我的测试账号"));
        assertThat(jdbc.queryForObject("SELECT content FROM long_term_memory", String.class))
            .isEqualTo("[REDACTED_ACCOUNT] 是我的测试账号");
    }

    @Test void latestMasterRetrievalNeverReturnsSupersededOrEndedRelations() {
        JdbcTemplate jdbc = database("retrieval");
        for (String name : List.of("user", "RetiredTool", "ActiveTool")) {
            jdbc.update("INSERT INTO kg_entity(id,user_id,normalized_name,display_name,entity_type) VALUES(?,?,?,?,?)",
                name, "u", name.toLowerCase(), name, "tool");
        }
        for (String name : List.of("RetiredTool", "ActiveTool")) {
            jdbc.update("INSERT INTO kg_relation(id,user_id,source_entity_id,target_entity_id,predicate,confidence,fact_status) VALUES(?,'u','user',?,'uses',.95,?)",
                name, name, name.equals("RetiredTool") ? "SUPERSEDED" : "ACTIVE");
            jdbc.update("INSERT INTO kg_evidence(user_id,turn_hash,relation_id,user_message) VALUES('u',?,?,?)",
                "trace-" + name, name, "我使用" + name);
        }
        var retrieval = new KnowledgeGraphRetrievalService(jdbc, mock(VectorSearchService.class),
            new MemoryRetrievalPolicy(java.time.Clock.systemUTC(), false, false));
        assertThat(retrieval.retrieve("u", "我使用什么工具", null, 10))
            .extracting(KnowledgeGraphRetrievalService.Fact::target).containsExactly("ActiveTool");
        jdbc.update("UPDATE kg_relation SET fact_status='ENDED' WHERE id='ActiveTool'");
        assertThat(retrieval.retrieve("u", "我使用什么工具", null, 10)).isEmpty();
    }
}
