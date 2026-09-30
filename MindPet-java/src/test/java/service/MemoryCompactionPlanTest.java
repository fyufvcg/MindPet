package service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteDataSource;
import util.Logger;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** In-process regression checks only. No models, embeddings, or experiment runner are invoked. */
class MemoryCompactionPlanTest {
    @TempDir Path temp;

    private record Services(JdbcTemplate jdbc, CuratorTurnStore turns, MemoryCorpusCompactionService corpus,
                            MemoryFactService facts, MemoryCuratorCommitService commit, TransactionTemplate transaction) {}

    private Services services(String name) {
        SQLiteDataSource data = new SQLiteDataSource();
        data.setUrl("jdbc:sqlite:" + temp.resolve(name));
        new ResourceDatabasePopulator(new ClassPathResource("db/sqlite-schema.sql")).execute(data);
        JdbcTemplate jdbc = new JdbcTemplate(data);
        Logger logger = new Logger();
        CuratorTurnStore turns = new CuratorTurnStore(jdbc, new ObjectMapper(), logger);
        Clock clock = Clock.fixed(Instant.parse("2026-09-30T00:00:00Z"), ZoneId.of("Asia/Shanghai"));
        MemoryFactService facts = new MemoryFactService(jdbc, clock);
        MemoryCorpusCompactionService corpus = new MemoryCorpusCompactionService(jdbc, null, logger);
        MemoryCuratorCommitService commit = new MemoryCuratorCommitService(facts, new ProfileProjectionService(jdbc, clock),
            new UserInsightService(jdbc, null, null, logger), turns, logger, clock, corpus);
        return new Services(jdbc, turns, corpus, facts, commit, new TransactionTemplate(new DataSourceTransactionManager(data)));
    }

    private long append(Services s, String id, String message, int day) {
        long sequence = s.turns.append("u", id, "session", "synthetic", message, "",
            Instant.parse("2026-09-01T00:00:00Z").plusSeconds(day * 86400L), ZoneId.of("Asia/Shanghai"));
        s.jdbc.update("INSERT INTO long_term_memory(user_id,session_id,content,role,importance) VALUES('u','session',?,'user',0.9)", message);
        return sequence;
    }

    private Map<String, Object> fact(String id, String predicate, String value, String scope, String assertion, String evidence) {
        return Map.of("predicate", predicate, "value", value, "scope", scope, "assertion", assertion,
            "confidence", 0.95, "source_turn_ids", List.of(id), "evidence", List.of(evidence),
            "action", "KEEP", "replaces_unit_ids", List.of("invalid-llm-id"), "retrieval_text", "");
    }

    private void commit(Services s, long sequence, List<Map<String, Object>> facts) {
        s.transaction.executeWithoutResult(status -> s.commit.commit("u", Map.of("facts", facts),
            s.turns.recentPending("u", 20), sequence, Map.of()));
    }

    @Test void resolvesBadLlmIdsAndCompressesOnlyFullyCoveredComposite() {
        Services s = services("coverage.sqlite");
        long seq = append(s, "one", "我现在住在苏州，我目前的职位是数据分析师。", 1);
        append(s, "two", "我现在住在苏州，还有一份需要逐项核对的附件。", 2);
        seq = s.turns.sequenceFor("u", "two");
        commit(s, seq, List.of(fact("one", "current_location", "苏州", "current", "observed", "我现在住在苏州"),
            fact("one", "occupation_current", "数据分析师", "current", "observed", "我目前的职位是数据分析师"),
            fact("two", "current_location", "苏州", "current", "observed", "我现在住在苏州")));
        assertThat(s.jdbc.queryForObject("SELECT searchable FROM long_term_memory WHERE content LIKE '%数据分析师%'", Integer.class)).isZero();
        assertThat(s.jdbc.queryForObject("SELECT searchable FROM long_term_memory WHERE content LIKE '%附件%'", Integer.class)).isEqualTo(1);
        assertThat(s.jdbc.queryForObject("SELECT COUNT(*) FROM memory_fact", Integer.class)).isEqualTo(2);
        assertThat(s.jdbc.queryForObject("SELECT reason FROM memory_compaction_plan WHERE resolved_action='KEEP'", String.class))
            .isEqualTo("uncovered_source_clause");
        s.transaction.executeWithoutResult(status -> s.corpus.rollbackBatch("u", s.turns.checkpoint("u")));
        assertThat(s.jdbc.queryForObject("SELECT COUNT(*) FROM long_term_memory WHERE searchable=1", Integer.class)).isEqualTo(2);
        assertThat(s.jdbc.queryForObject("SELECT COUNT(*) FROM memory_retrieval_unit WHERE status='rolled_back'", Integer.class)).isEqualTo(2);
    }

    @Test void incompleteProposalRollsBackWithoutAnyFactOrRawMutation() {
        Services s = services("invalid.sqlite");
        long seq = append(s, "one", "我现在住在苏州。", 1);
        assertThatThrownBy(() -> commit(s, seq, List.of(fact("one", "current_location", "苏州", "current", "observed", "我现在住在苏州"),
            fact("missing", "identity", "小林", "stable", "observed", "我叫小林"))))
            .isInstanceOf(MemoryCuratorCommitService.ProposalRejectedException.class);
        assertThat(s.jdbc.queryForObject("SELECT COUNT(*) FROM memory_fact", Integer.class)).isZero();
        assertThat(s.jdbc.queryForObject("SELECT COUNT(*) FROM memory_compaction_batch", Integer.class)).isZero();
        assertThat(s.jdbc.queryForObject("SELECT searchable FROM long_term_memory", Integer.class)).isEqualTo(1);
    }

    @Test void missingTurnStaysSearchableAndBackfillsLater() {
        Services s = services("backfill.sqlite");
        s.jdbc.update("INSERT INTO long_term_memory(user_id,session_id,content,role) VALUES('u','session','我现在住在苏州。','user')");
        s.corpus.ensureLegacyIndexed("u");
        assertThat(s.corpus.planCompaction("u", 1).decisions().get(0).reason()).isEqualTo("provenance_pending");
        assertThat(s.jdbc.queryForObject("SELECT searchable FROM memory_retrieval_unit", Integer.class)).isEqualTo(1);
        s.turns.append("u", "late-source", "session", "synthetic", "我现在住在苏州。", "",
            Instant.parse("2026-09-01T00:00:00Z"), ZoneId.of("Asia/Shanghai"));
        s.corpus.ensureLegacyIndexed("u");
        assertThat(s.jdbc.queryForObject("SELECT source_turn_id FROM memory_retrieval_source", String.class)).isEqualTo("late-source");
    }

    @Test void extraIndependentDetailInSameClauseBlocksCompaction() {
        Services s = services("residual.sqlite");
        long seq = append(s, "one", "我现在住在苏州的紫竹路十八号。", 1);
        commit(s, seq, List.of(fact("one", "current_location", "苏州", "current", "observed", "我现在住在苏州的紫竹路十八号")));
        assertThat(s.jdbc.queryForObject("SELECT searchable FROM long_term_memory", Integer.class)).isEqualTo(1);
        assertThat(s.jdbc.queryForObject("SELECT reason FROM memory_compaction_plan", String.class)).isEqualTo("uncovered_source_clause");
    }

    @Test void plannedStateAdvancesAndCancellationRetainsHistoricalEvidence() {
        Services s = services("plan.sqlite");
        long a = append(s, "possible", "我可能参加摄影课程。", 1);
        commit(s, a, List.of(fact("possible", "plan", "摄影课程", "planned", "possible", "我可能参加摄影课程")));
        long b = append(s, "planned", "我计划参加摄影课程。", 2);
        commit(s, b, List.of(fact("planned", "plan", "摄影课程", "planned", "planned", "我计划参加摄影课程")));
        long c = append(s, "cancel", "我已经取消摄影课程的计划。", 3);
        commit(s, c, List.of(fact("cancel", "plan", "摄影课程", "planned", "negated", "我已经取消摄影课程的计划")));
        assertThat(s.jdbc.queryForObject("SELECT COUNT(*) FROM memory_fact WHERE status='proposed'", Integer.class)).isZero();
        assertThat(s.jdbc.queryForObject("SELECT COUNT(*) FROM memory_retrieval_unit WHERE searchable=1 AND status='historical'", Integer.class)).isEqualTo(2);
        assertThat(s.jdbc.queryForObject("SELECT COUNT(*) FROM long_term_memory WHERE searchable=1", Integer.class)).isZero();
    }

    @Test void returnToEarlierValueStartsNewIntervalAndConfirmationKeepsItsStart() {
        Services s = services("interval.sqlite");
        long a = append(s, "a", "我现在住在杭州。", 1);
        commit(s, a, List.of(fact("a", "current_location", "杭州", "current", "observed", "我现在住在杭州")));
        long b = append(s, "b", "我现在住在苏州。", 2);
        commit(s, b, List.of(fact("b", "current_location", "苏州", "current", "observed", "我现在住在苏州")));
        long c = append(s, "c", "我现在住在杭州。", 3);
        commit(s, c, List.of(fact("c", "current_location", "杭州", "current", "observed", "我现在住在杭州")));
        long d = append(s, "d", "我现在住在杭州。", 4);
        commit(s, d, List.of(fact("d", "current_location", "杭州", "current", "confirmed", "我现在住在杭州")));
        assertThat(s.jdbc.queryForObject("SELECT COUNT(*) FROM memory_fact WHERE value_text='杭州'", Integer.class)).isEqualTo(2);
        assertThat(s.jdbc.queryForObject("SELECT COUNT(*) FROM memory_fact WHERE status='active'", Integer.class)).isEqualTo(1);
        assertThat(s.jdbc.queryForObject("SELECT evidence_count FROM memory_fact WHERE status='active'", Integer.class)).isEqualTo(2);
    }

    @Test void groupedSourcesPreserveReturnToOldValueInOneBatch() {
        Services s = services("grouped.sqlite");
        append(s, "a", "我现在住在杭州。", 1);
        append(s, "b", "我现在住在苏州。", 2);
        long sequence = append(s, "c", "我现在住在杭州。", 3);
        Map<String, Object> grouped = new java.util.LinkedHashMap<>(fact("a", "current_location", "杭州", "current", "observed", "我现在住在杭州"));
        grouped.put("source_turn_ids", List.of("a", "c")); grouped.put("evidence", List.of("我现在住在杭州", "我现在住在杭州"));
        commit(s, sequence, List.of(grouped, fact("b", "current_location", "苏州", "current", "observed", "我现在住在苏州")));
        assertThat(s.jdbc.queryForObject("SELECT COUNT(*) FROM memory_fact WHERE value_text='杭州'", Integer.class)).isEqualTo(2);
        assertThat(s.jdbc.queryForObject("SELECT value_text FROM memory_fact WHERE status='active'", String.class)).isEqualTo("杭州");
        assertThat(s.jdbc.queryForObject("SELECT source_turn_id FROM memory_fact WHERE status='active'", String.class)).isEqualTo("c");
    }

    @Test void completedPlanRollbackRestoresProposalAndCheckpoint() {
        Services s = services("completed.sqlite");
        long a = append(s, "planned", "我计划参加摄影课程。", 1);
        commit(s, a, List.of(fact("planned", "plan", "摄影课程", "planned", "planned", "我计划参加摄影课程")));
        long b = append(s, "done", "我已经参加摄影课程。", 2);
        commit(s, b, List.of(fact("done", "plan", "摄影课程", "episodic", "observed", "我已经参加摄影课程")));
        assertThat(s.jdbc.queryForObject("SELECT COUNT(*) FROM memory_fact WHERE scope='planned' AND status='proposed'", Integer.class)).isZero();
        s.transaction.executeWithoutResult(status -> s.corpus.rollbackBatch("u", b));
        assertThat(s.turns.checkpoint("u")).isEqualTo(a);
        assertThat(s.turns.pendingCount("u")).isEqualTo(1);
        assertThat(s.jdbc.queryForObject("SELECT COUNT(*) FROM memory_fact WHERE scope='planned' AND status='proposed'", Integer.class)).isEqualTo(1);
    }

    @Test void rolledBackNegationDoesNotReappearAndCanBeRetried() {
        Services s = services("negated-rollback.sqlite");
        long sequence = append(s, "cancel", "我已经取消摄影课程的计划。", 1);
        var facts = List.of(fact("cancel", "plan", "摄影课程", "planned", "negated", "我已经取消摄影课程的计划"));
        commit(s, sequence, facts);
        s.transaction.executeWithoutResult(status -> s.corpus.rollbackBatch("u", sequence));
        s.corpus.ensureLegacyIndexed("u"); s.corpus.reconcileFactStatuses("u", 0);
        assertThat(s.jdbc.queryForObject("SELECT COUNT(*) FROM memory_retrieval_unit WHERE unit_type='fact' AND searchable=1", Integer.class)).isZero();
        commit(s, sequence, facts);
        assertThat(s.jdbc.queryForObject("SELECT COUNT(*) FROM memory_retrieval_unit WHERE unit_type='fact' AND searchable=1", Integer.class)).isEqualTo(1);
        s.transaction.executeWithoutResult(status -> s.corpus.rollbackBatch("u", sequence));
        assertThat(s.jdbc.queryForObject("SELECT status FROM memory_fact", String.class)).isEqualTo("rolled_back");
    }

    @Test void editedAndDeletedRawInvalidateOldRetrievalText() {
        Services s = services("edited.sqlite");
        append(s, "one", "我现在住在苏州。", 1);
        s.corpus.ensureLegacyIndexed("u");
        s.jdbc.update("UPDATE long_term_memory SET content='我现在住在南京。'");
        s.corpus.ensureLegacyIndexed("u");
        assertThat(s.jdbc.queryForObject("SELECT content FROM memory_retrieval_unit WHERE searchable=1", String.class)).isEqualTo("我现在住在南京。");
        s.jdbc.update("DELETE FROM long_term_memory"); s.corpus.ensureLegacyIndexed("u");
        assertThat(s.jdbc.queryForObject("SELECT COUNT(*) FROM memory_retrieval_unit WHERE searchable=1", Integer.class)).isZero();
    }
}
