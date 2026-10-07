package config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteStorageV3MigrationTest {

    @TempDir
    Path tempDir;

    @Test
    void upgradesLegacyKnowledgeGraphAdditivelyWithoutDroppingRows() throws Exception {
        Path file = tempDir.resolve("legacy-v28.db");
        SQLiteDataSource legacy = new SQLiteDataSource();
        legacy.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
        try (Connection connection = legacy.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE kg_entity (id TEXT PRIMARY KEY,user_id TEXT NOT NULL,"
                + "normalized_name TEXT NOT NULL,display_name TEXT NOT NULL,entity_type TEXT NOT NULL,"
                + "summary TEXT NOT NULL DEFAULT '',embedding BLOB,importance REAL NOT NULL DEFAULT 0.5,"
                + "mention_count INTEGER NOT NULL DEFAULT 1,first_seen TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "last_seen TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,UNIQUE(user_id,normalized_name,entity_type))");
            statement.execute("CREATE TABLE kg_relation (id TEXT PRIMARY KEY,user_id TEXT NOT NULL,"
                + "source_entity_id TEXT NOT NULL,target_entity_id TEXT NOT NULL,predicate TEXT NOT NULL,"
                + "confidence REAL NOT NULL DEFAULT 0.5,importance REAL NOT NULL DEFAULT 0.5,"
                + "mention_count INTEGER NOT NULL DEFAULT 1,first_seen TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "last_seen TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "UNIQUE(user_id,source_entity_id,target_entity_id,predicate))");
            statement.execute("CREATE TABLE kg_turn_ingest (turn_hash TEXT PRIMARY KEY,user_id TEXT NOT NULL,"
                + "session_id TEXT NOT NULL DEFAULT '',entity_count INTEGER NOT NULL DEFAULT 0,"
                + "relation_count INTEGER NOT NULL DEFAULT 0,created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            statement.execute("INSERT INTO kg_entity(id,user_id,normalized_name,display_name,entity_type) "
                + "VALUES('legacy-entity','u','legacy','Legacy','tool')");
        }

        Method migration = SqliteStorageConfig.class.getDeclaredMethod(
            "migrateV3MemoryWriteSchema", DataSource.class);
        migration.setAccessible(true);
        migration.invoke(new SqliteStorageConfig(), legacy);
        ResourceDatabasePopulator schema = new ResourceDatabasePopulator(new ClassPathResource("db/sqlite-schema.sql"));
        schema.execute(legacy);
        schema.execute(legacy); // The lifecycle journal can be installed idempotently on an existing DB.

        assertThat(columns(legacy, "kg_relation")).contains(
            "semantic_predicate", "resolution_kind", "fact_status", "temporal_status",
            "valid_from", "valid_to", "superseded_by");
        assertThat(columns(legacy, "kg_turn_ingest")).contains(
            "pipeline_version", "memory_type", "store_decision");
        assertThat(columns(legacy, "kg_fact_event")).contains(
            "importance", "confidence", "session_id", "turn_hash", "semantic_predicate",
            "prior_predicate", "prior_semantic_predicate", "relation_id", "user_message");
        try (Connection connection = legacy.getConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM kg_entity WHERE id='legacy-entity'")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(1);
        }
    }

    private Set<String> columns(DataSource dataSource, String table) throws Exception {
        Set<String> columns = new HashSet<>();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) columns.add(result.getString("name"));
        }
        return columns;
    }
}
