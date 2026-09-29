package config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

@Configuration
public class SqliteStorageConfig {

    @Bean
    public Clock memoryCuratorClock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    @Primary
    public DataSource sqliteDataSource(
        @Value("${app.storage.sqlite.path:${MINDPET_DATA_DIR:${user.home}/.mindpet}/mindpet.db}") String configuredPath,
        @Value("${app.storage.sqlite.vec-extension:}") String extensionPath
    ) throws Exception {
        Path dbPath = Path.of(configuredPath).toAbsolutePath().normalize();
        Files.createDirectories(dbPath.getParent());

        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setBusyTimeout(10_000);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.enableLoadExtension(true);

        SQLiteDataSource sqlite = new SQLiteDataSource(config);
        sqlite.setUrl("jdbc:sqlite:" + dbPath);
        HikariConfig poolConfig = new HikariConfig();
        poolConfig.setDataSource(sqlite);
        poolConfig.setMaximumPoolSize(1);
        poolConfig.setMinimumIdle(1);
        poolConfig.setPoolName("MindPet-SQLite");
        Path extension = SqliteNative.resolveExtension(extensionPath);
        if (extension != null) poolConfig.setConnectionInitSql("SELECT load_extension('" + SqliteNative.sqlPath(extension) + "')");
        HikariDataSource dataSource = new HikariDataSource(poolConfig);
        ResourceDatabasePopulator initializer = new ResourceDatabasePopulator(
            new ClassPathResource("db/sqlite-schema.sql"));
        initializer.setContinueOnError(false);
        initializer.execute(dataSource);
        migrateGrowthReflectionColumns(dataSource);
        migrateMemoryConsolidation(dataSource);
        return dataSource;
    }

    private void migrateMemoryConsolidation(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                Set<String> stateColumns = tableColumns(statement, "curator_state");
                addColumnIfMissing(statement, "curator_state", stateColumns, "last_turn_id", "TEXT");
                addColumnIfMissing(statement, "curator_state", stateColumns, "last_success_at", "TEXT");
                addColumnIfMissing(statement, "curator_state", stateColumns, "last_error", "TEXT");
                addColumnIfMissing(statement, "curator_state", stateColumns, "retry_count", "INTEGER NOT NULL DEFAULT 0");
                addColumnIfMissing(statement, "curator_state", stateColumns, "retry_after", "TEXT");

                Set<String> turnColumns = tableColumns(statement, "curator_turns");
                addColumnIfMissing(statement, "curator_turns", turnColumns, "occurred_at", "TEXT");
                addColumnIfMissing(statement, "curator_turns", turnColumns, "event_timezone", "TEXT");
                addColumnIfMissing(statement, "curator_turns", turnColumns, "processed_at", "TEXT");
                addColumnIfMissing(statement, "curator_turns", turnColumns, "consolidation_status", "TEXT NOT NULL DEFAULT 'pending'");
                migrateGlobalCuratorTurnIdConstraint(connection, statement);

                statement.execute("CREATE TABLE IF NOT EXISTS memory_fact ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, user_id TEXT NOT NULL, "
                    + "predicate TEXT NOT NULL, value_text TEXT NOT NULL DEFAULT '', "
                    + "value_json TEXT NOT NULL DEFAULT '', scope TEXT NOT NULL DEFAULT 'episodic', "
                    + "assertion TEXT NOT NULL DEFAULT 'observed', confidence REAL NOT NULL DEFAULT 0.5, "
                    + "valid_from TEXT, valid_to TEXT, observed_at TEXT, event_timezone TEXT, "
                    + "raw_time_expression TEXT NOT NULL DEFAULT '', normalized_start TEXT, normalized_end TEXT, "
                    + "time_precision TEXT NOT NULL DEFAULT 'unknown', time_status TEXT NOT NULL DEFAULT 'unresolved', "
                    + "source_turn_id TEXT, raw_text TEXT NOT NULL DEFAULT '', status TEXT NOT NULL DEFAULT 'active', "
                    + "supersedes_id INTEGER, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "UNIQUE(user_id, source_turn_id, predicate, value_text, normalized_start))");
                statement.execute("CREATE TABLE IF NOT EXISTS user_profile_current ("
                    + "user_id TEXT NOT NULL, slot_key TEXT NOT NULL, value TEXT NOT NULL, "
                    + "source_fact_id INTEGER, confidence REAL NOT NULL DEFAULT 0.5, valid_from TEXT, "
                    + "valid_to TEXT, updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "PRIMARY KEY(user_id, slot_key))");
                String rankedFacts = "SELECT id,user_id,COALESCE(source_turn_id,'') AS source_turn_key,predicate,value_text,"
                    + "COALESCE(normalized_start,'') AS normalized_start_key,ROW_NUMBER() OVER ("
                    + "PARTITION BY user_id,COALESCE(source_turn_id,''),predicate,value_text,COALESCE(normalized_start,'') "
                    + "ORDER BY CASE WHEN COALESCE(raw_text,'')<>'' THEN 0 ELSE 1 END,"
                    + "COALESCE(updated_at,created_at) DESC,id DESC) AS duplicate_rank FROM memory_fact";
                statement.execute("WITH ranked AS (" + rankedFacts + "), duplicate_map AS ("
                    + "SELECT duplicate.id AS duplicate_id,keeper.id AS keeper_id FROM ranked duplicate "
                    + "JOIN ranked keeper ON keeper.user_id=duplicate.user_id "
                    + "AND keeper.source_turn_key=duplicate.source_turn_key AND keeper.predicate=duplicate.predicate "
                    + "AND keeper.value_text=duplicate.value_text AND keeper.normalized_start_key=duplicate.normalized_start_key "
                    + "AND keeper.duplicate_rank=1 WHERE duplicate.duplicate_rank>1) "
                    + "UPDATE memory_fact SET supersedes_id=(SELECT CASE WHEN memory_fact.id=duplicate_map.keeper_id "
                    + "THEN NULL ELSE duplicate_map.keeper_id END FROM duplicate_map "
                    + "WHERE duplicate_map.duplicate_id=memory_fact.supersedes_id) "
                    + "WHERE supersedes_id IN (SELECT duplicate_id FROM duplicate_map)");
                statement.execute("DELETE FROM memory_fact WHERE id IN (SELECT id FROM (" + rankedFacts
                    + ") WHERE duplicate_rank>1)");
                statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_memory_fact_idempotency "
                    + "ON memory_fact(user_id,IFNULL(source_turn_id,''),predicate,value_text,IFNULL(normalized_start,''))");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_memory_fact_user_predicate_status "
                    + "ON memory_fact(user_id, predicate, status, normalized_start)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_memory_fact_user_source "
                    + "ON memory_fact(user_id, source_turn_id)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_curator_turns_pending "
                    + "ON curator_turns(user_id, consolidation_status, occurred_at)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_profile_current_user_updated "
                    + "ON user_profile_current(user_id, updated_at DESC)");
                statement.execute("INSERT OR IGNORE INTO user_profile_current(user_id,slot_key,value,confidence,updated_at) "
                    + "SELECT user_id,prop_key,prop_value,0.75,updated_at FROM user_profile "
                    + "WHERE category='state' AND prop_key IN "
                    + "('current_location','home_location','occupation_current','relationship_status_current','current_project')");
                statement.execute("INSERT OR IGNORE INTO schema_version(version) VALUES (2)");
                connection.commit();
            } catch (Exception migrationError) {
                connection.rollback();
                throw migrationError;
            }
        }
    }

    private Set<String> tableColumns(Statement statement, String table) throws Exception {
        Set<String> columns = new HashSet<>();
        try (ResultSet result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) columns.add(result.getString("name"));
        }
        return columns;
    }

    private void migrateGlobalCuratorTurnIdConstraint(Connection connection, Statement statement) throws Exception {
        if (!hasUniqueIndexColumns(connection, "curator_turns", Set.of("turn_id"))) return;

        statement.execute("ALTER TABLE curator_turns RENAME TO curator_turns_global_id_migration");
        statement.execute("CREATE TABLE curator_turns ("
            + "sequence INTEGER PRIMARY KEY AUTOINCREMENT, user_id TEXT NOT NULL, turn_id TEXT NOT NULL, "
            + "session_id TEXT, source TEXT, user_message TEXT, assistant_reply TEXT, completed_at TEXT NOT NULL, "
            + "occurred_at TEXT, event_timezone TEXT, processed_at TEXT, "
            + "consolidation_status TEXT NOT NULL DEFAULT 'pending', UNIQUE(user_id, turn_id))");
        statement.execute("INSERT INTO curator_turns(sequence,user_id,turn_id,session_id,source,user_message,"
            + "assistant_reply,completed_at,occurred_at,event_timezone,processed_at,consolidation_status) "
            + "SELECT sequence,user_id,turn_id,session_id,source,user_message,assistant_reply,completed_at,"
            + "occurred_at,event_timezone,processed_at,consolidation_status FROM curator_turns_global_id_migration");
        statement.execute("DROP TABLE curator_turns_global_id_migration");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_curator_turns_user_seq "
            + "ON curator_turns(user_id, sequence DESC)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_curator_turns_user_completed "
            + "ON curator_turns(user_id, completed_at DESC)");
    }

    private boolean hasUniqueIndexColumns(Connection connection, String table,
                                          Set<String> expectedColumns) throws Exception {
        try (Statement indexesStatement = connection.createStatement();
             ResultSet indexes = indexesStatement.executeQuery("PRAGMA index_list(" + table + ")")) {
            while (indexes.next()) {
                if (indexes.getInt("unique") != 1) continue;
                String indexName = indexes.getString("name");
                try (Statement indexStatement = connection.createStatement();
                     ResultSet indexColumns = indexStatement.executeQuery(
                         "PRAGMA index_info('" + indexName.replace("'", "''") + "')")) {
                    Set<String> columns = new HashSet<>();
                    while (indexColumns.next()) columns.add(indexColumns.getString("name"));
                    if (columns.equals(expectedColumns)) return true;
                }
            }
        }
        return false;
    }

    private void migrateGrowthReflectionColumns(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                Set<String> columns = new HashSet<>();
                try (ResultSet result = statement.executeQuery("PRAGMA table_info(llm_growth)")) {
                    while (result.next()) columns.add(result.getString("name"));
                }

                addColumnIfMissing(statement, "llm_growth", columns, "title", "TEXT NOT NULL DEFAULT ''");
                addColumnIfMissing(statement, "llm_growth", columns, "source_type", "TEXT NOT NULL DEFAULT ''");
                addColumnIfMissing(statement, "llm_growth", columns, "source_id", "TEXT NOT NULL DEFAULT ''");
                addColumnIfMissing(statement, "llm_growth", columns, "updated_at", "TEXT NOT NULL DEFAULT ''");
                if (hasLegacyGrowthTextUniqueIndex(connection, statement)) {
                    rebuildGrowthWithoutTextUniqueConstraint(statement);
                }
                statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_llm_growth_source "
                    + "ON llm_growth(user_id, category, source_type, source_id) "
                    + "WHERE source_type <> '' AND source_id <> ''");
                connection.commit();
            } catch (Exception migrationError) {
                connection.rollback();
                throw migrationError;
            }
        }
    }

    private boolean hasLegacyGrowthTextUniqueIndex(Connection connection, Statement statement) throws Exception {
        Set<String> uniqueIndexes = new HashSet<>();
        try (ResultSet indexes = statement.executeQuery("PRAGMA index_list(llm_growth)")) {
            while (indexes.next()) {
                if (indexes.getInt("unique") == 1) uniqueIndexes.add(indexes.getString("name"));
            }
        }
        for (String indexName : uniqueIndexes) {
            try (Statement indexStatement = connection.createStatement();
                 ResultSet indexColumns = indexStatement.executeQuery(
                     "PRAGMA index_info('" + indexName.replace("'", "''") + "')")) {
                Set<String> columns = new HashSet<>();
                while (indexColumns.next()) columns.add(indexColumns.getString("name"));
                if (columns.equals(Set.of("user_id", "category", "insight"))) return true;
            }
        }
        return false;
    }

    private void rebuildGrowthWithoutTextUniqueConstraint(Statement statement) throws Exception {
        statement.execute("CREATE TABLE llm_growth_migration ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
            + "user_id TEXT NOT NULL, category TEXT NOT NULL, insight TEXT NOT NULL, "
            + "context TEXT NOT NULL DEFAULT '', embedding BLOB, "
            + "title TEXT NOT NULL DEFAULT '', source_type TEXT NOT NULL DEFAULT '', "
            + "source_id TEXT NOT NULL DEFAULT '', "
            + "created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, "
            + "updated_at TEXT NOT NULL DEFAULT '')");
        statement.execute("INSERT INTO llm_growth_migration "
            + "(id,user_id,category,insight,context,embedding,title,source_type,source_id,created_at,updated_at) "
            + "SELECT id,user_id,category,insight,context,embedding,title,source_type,source_id,created_at,updated_at "
            + "FROM llm_growth");
        statement.execute("DROP TABLE llm_growth");
        statement.execute("ALTER TABLE llm_growth_migration RENAME TO llm_growth");
    }

    private void addColumnIfMissing(Statement statement, String table, Set<String> columns,
                                    String column, String declaration) throws Exception {
        if (columns.contains(column)) return;
        statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + declaration);
    }
}
