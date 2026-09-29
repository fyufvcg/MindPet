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

class MemoryCuratorCommitServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void fullyRejectedRepairableProposalDoesNotAdvanceCheckpoint() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("checkpoint.db").toAbsolutePath());
        new ResourceDatabasePopulator(new ClassPathResource("db/sqlite-schema.sql")).execute(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Logger logger = new Logger();
        CuratorTurnStore turns = new CuratorTurnStore(jdbc, new ObjectMapper(), logger);
        Clock clock = Clock.fixed(Instant.parse("2026-09-29T04:00:00Z"), ZoneId.of("Asia/Shanghai"));
        MemoryFactService facts = new MemoryFactService(jdbc, clock);
        ProfileProjectionService profiles = new ProfileProjectionService(jdbc, clock);
        UserInsightService insights = new UserInsightService(jdbc, null, null, logger);
        MemoryCuratorCommitService commit = new MemoryCuratorCommitService(
            facts, profiles, insights, turns, logger, clock);
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        turns.append("u", "turn-1", "session-1", "synthetic", "我目前在广州。", "",
            Instant.parse("2026-09-28T04:00:00Z"), ZoneId.of("Asia/Shanghai"));
        List<CuratorTurnStore.CompletedTurn> batch = turns.recentPending("u", 10);
        long target = turns.sequenceFor("u", "turn-1");
        Map<String, Object> invalidProposal = Map.of(
            "facts", List.of(Map.of("predicate", "made_up_location", "value", "广州", "scope", "current",
                "assertion", "observed", "confidence", 0.95, "source_turn_id", "turn-1", "evidence", "我目前在广州。")),
            "insights", List.of(),
            "growth", List.of());

        assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
            commit.commit("u", invalidProposal, batch, target, Map.of())))
            .isInstanceOf(MemoryCuratorCommitService.ProposalRejectedException.class);
        assertThat(turns.checkpoint("u")).isZero();
        assertThat(turns.pendingCount("u")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memory_fact WHERE user_id='u'", Integer.class)).isZero();
    }
}
