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

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
