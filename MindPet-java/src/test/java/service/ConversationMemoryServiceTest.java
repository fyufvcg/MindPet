package service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.sqlite.SQLiteDataSource;
import tool.ToolUserContext;
import util.Logger;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationMemoryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void partialStreamSurvivesRestartAndCompletionUpdatesTheSameMessages() {
        Path databaseFile = tempDir.resolve("conversation-memory.db");
        ToolUserContext.set("desktop-user", "session-1");
        try {
            ConversationMemoryService writer = service(database(databaseFile));
            writer.upsert("desktop-user", message("turn-1-user", "user", "帮我分析一下", "completed"));
            writer.upsert("desktop-user", message("turn-1-agent", "assistant", "先检查", "in_progress"));
            writer.upsert("desktop-user", message("turn-1-agent", "assistant", "先检查了日志，发现", "incomplete"));

            ConversationMemoryService reopened = service(database(databaseFile));
            List<Map<String, Object>> recovered = reopened.loadRecent("desktop-user", 20);
            assertThat(recovered).hasSize(2);
            assertThat(recovered).extracting(row -> row.get("role"))
                .containsExactly("user", "assistant");
            assertThat(recovered.get(1))
                .containsEntry("content", "先检查了日志，发现")
                .containsEntry("status", "incomplete");

            reopened.upsert("desktop-user", message(
                "turn-1-agent", "assistant", "先检查了日志，发现两分钟超时", "completed"));

            List<Map<String, Object>> completed = reopened.loadRecent("desktop-user", 20);
            assertThat(completed).hasSize(2);
            assertThat(completed.get(1))
                .containsEntry("content", "先检查了日志，发现两分钟超时")
                .containsEntry("status", "completed");
        } finally {
            ToolUserContext.clear();
        }
    }

    private ConversationMemoryService service(JdbcTemplate jdbc) {
        return new ConversationMemoryService(jdbc, new ObjectMapper(), new Logger());
    }

    private JdbcTemplate database(Path file) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
        new ResourceDatabasePopulator(new ClassPathResource("db/sqlite-schema.sql")).execute(dataSource);
        return new JdbcTemplate(dataSource);
    }

    private Map<String, Object> message(String id, String role, String content, String status) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", id);
        message.put("role", role);
        message.put("content", content);
        message.put("status", status);
        return message;
    }
}
