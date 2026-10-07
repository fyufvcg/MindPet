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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StorageRegressionTest {

    @TempDir
    Path tempDir;

    @Test
    void sessionMessagesSurviveReopeningTheDatabase() {
        Path database = tempDir.resolve("mindpet.db");
        JdbcTemplate first = database(database);
        SqliteSessionStore writer = new SqliteSessionStore(first, new ObjectMapper(), new Logger());

        writer.upsertSession("desktop-user", "session-1", Map.of("name", "Persistent"));
        writer.appendMessage("desktop-user", "session-1",
            Map.of("id", "message-1", "sender", "user", "text", "hello"));

        SqliteSessionStore reader = new SqliteSessionStore(database(database), new ObjectMapper(), new Logger());
        assertThat(reader.listSessions("desktop-user"))
            .extracting(row -> row.get("id"))
            .containsExactly("session-1");
        assertThat(reader.loadMessages("desktop-user", "session-1", 20))
            .extracting(row -> row.get("text"))
            .containsExactly("hello");
    }

    @Test
    void javaVectorFallbackUsesCosineDistanceAndKeepsOrder() {
        JdbcTemplate jdbc = database(tempDir.resolve("vectors.db"));
        jdbc.update("INSERT INTO long_term_memory(user_id,content,role,embedding) VALUES(?,?,?,?)",
            "desktop-user", "nearest", "user", VectorSearchService.encode(new float[]{1f, 0f}));
        jdbc.update("INSERT INTO long_term_memory(user_id,content,role,embedding) VALUES(?,?,?,?)",
            "desktop-user", "farther", "user", VectorSearchService.encode(new float[]{0f, 1f}));

        VectorSearchService vectors = new VectorSearchService(jdbc, new Logger(), "");
        List<VectorSearchService.VectorMatch> matches =
            vectors.search("long_term_memory", "desktop-user", new float[]{1f, 0f}, 2);

        assertThat(matches).hasSize(2);
        assertThat(matches.get(0).distance()).isCloseTo(0d, within(1e-9));
        assertThat(matches.get(1).distance()).isCloseTo(1d, within(1e-9));
    }

    private JdbcTemplate database(Path file) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
        new ResourceDatabasePopulator(new ClassPathResource("db/sqlite-schema.sql")).execute(dataSource);
        return new JdbcTemplate(dataSource);
    }

    @Test void legacyFactConstraintMigrationPreservesIdsAndAllowsDifferentStates() throws Exception {
        Path path = tempDir.resolve("legacy-facts.db");
        JdbcTemplate jdbc = database(path);
        jdbc.execute("PRAGMA foreign_keys=OFF");
        jdbc.execute("DROP TABLE memory_fact");
        String schema;
        try (var stream = new ClassPathResource("db/sqlite-schema.sql").getInputStream()) {
            schema = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        String definition = schema.substring(schema.indexOf("CREATE TABLE IF NOT EXISTS memory_fact ("));
        definition = definition.substring(0, definition.indexOf(';'))
            .replace("UNIQUE(user_id, source_turn_id, predicate, value_text, scope, assertion, normalized_start)",
                "UNIQUE(user_id, source_turn_id, predicate, value_text, normalized_start)");
        jdbc.execute(definition);
        jdbc.update("INSERT INTO memory_fact(id,user_id,predicate,value_text,scope,assertion,source_turn_id,normalized_start) "
            + "VALUES(42,'u','plan','摄影课程','planned','possible','turn','2026-09-01')");
        jdbc.update("INSERT INTO user_profile_current(user_id,slot_key,value,source_fact_id) VALUES('u','test','value',42)");
        var data = new config.SqliteStorageConfig().sqliteDataSource(
            path.toString(), tempDir.resolve("no-vector-extension").toString());
        try (var cleanup = (AutoCloseable) data) {
            JdbcTemplate migrated = new JdbcTemplate(data);
            migrated.update("INSERT INTO memory_fact(user_id,predicate,value_text,scope,assertion,source_turn_id,normalized_start) "
                + "VALUES('u','plan','摄影课程','planned','planned','turn','2026-09-01')");
            assertThat(migrated.queryForObject("SELECT source_fact_id FROM user_profile_current", Long.class)).isEqualTo(42);
            assertThat(migrated.queryForObject("SELECT COUNT(*) FROM memory_fact", Integer.class)).isEqualTo(2);
            assertThat(migrated.queryForList("PRAGMA foreign_key_check")).isEmpty();
            assertThat(migrated.queryForObject("PRAGMA foreign_keys", Integer.class)).isEqualTo(1);
        }
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
