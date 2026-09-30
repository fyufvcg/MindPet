package service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.sqlite.SQLiteDataSource;
import util.Logger;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class CuratorTurnStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void countsOnlyTheUserPendingTurns() {
        JdbcTemplate jdbc = database(tempDir.resolve("turns.db"));
        CuratorTurnStore store = new CuratorTurnStore(jdbc, new ObjectMapper(), new Logger());
        store.append("u", "s", "desktop", "hello", "reply",
            Instant.parse("2026-09-22T02:00:00Z"), ZoneId.of("Asia/Shanghai"));
        store.append("other", "s", "desktop", "hello", "reply",
            Instant.parse("2026-09-22T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

        assertThat(store.count("u")).isEqualTo(1);
        assertThat(store.pendingCount("u")).isEqualTo(1);
        assertThat(store.recentPending("u", 10)).hasSize(1);
        assertThat(store.recentPending("u", 10).get(0).occurredAt())
            .isEqualTo("2026-09-22T02:00:00Z");
    }

    @Test
    void processingAStoredTurnIsIdempotentAtStatusLevel() {
        JdbcTemplate jdbc = database(tempDir.resolve("status.db"));
        CuratorTurnStore store = new CuratorTurnStore(jdbc, new ObjectMapper(), new Logger());
        store.append("u", "s", "desktop", "hello", "reply");
        var turns = store.recentPending("u", 10);
        store.markProcessed("u", turns, "success");
        store.markProcessed("u", turns, "success");

        assertThat(store.pendingCount("u")).isZero();
        assertThat(store.count("u")).isEqualTo(1);
    }

    private JdbcTemplate database(Path file) {
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
        new ResourceDatabasePopulator(new ClassPathResource("db/sqlite-schema.sql")).execute(source);
        return new JdbcTemplate(source);
    }
}
