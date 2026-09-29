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
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

@Configuration
public class SqliteStorageConfig {

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

                Set<String> turnColumns = tableColumns(statement, "curator_turns");
                addColumnIfMissing(statement, "curator_turns", turnColumns, "occurred_at", "TEXT");
                addColumnIfMissing(statement, "curator_turns", turnColumns, "event_timezone", "TEXT");
                addColumnIfMissing(statement, "curator_turns", turnColumns, "processed_at", "TEXT");
                addColumnIfMissing(statement, "curator_turns", turnColumns, "consolidation_status", "TEXT NOT NULL DEFAULT 'pending'");

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
