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
        @Value("${app.storage.sqlite.vec-extension:}") String extensionPath,
        @Value("${app.eval.e2e-memory.enabled:${APP_EVAL_E2E_MEMORY_ENABLED:false}}") boolean evaluationEnabled,
        @Value("${app.eval.e2e-memory.sqlite-path:${APP_EVAL_E2E_MEMORY_SQLITE_PATH:}}") String evaluationPath,
        @Value("${app.eval.e2e-memory.allowed-root:${APP_EVAL_E2E_MEMORY_ALLOWED_ROOT:}}") String allowedRoot,
        @Value("${user.home}") String userHome
    ) throws Exception {
        if (evaluationEnabled) {
            EvaluationSqlitePathGuard.validate(
                configuredPath, evaluationPath, allowedRoot, userHome);
        }
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
        return dataSource;
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

                addColumnIfMissing(statement, columns, "title", "TEXT NOT NULL DEFAULT ''");
                addColumnIfMissing(statement, columns, "source_type", "TEXT NOT NULL DEFAULT ''");
                addColumnIfMissing(statement, columns, "source_id", "TEXT NOT NULL DEFAULT ''");
                addColumnIfMissing(statement, columns, "updated_at", "TEXT NOT NULL DEFAULT ''");
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

    private void addColumnIfMissing(Statement statement, Set<String> columns,
                                    String column, String declaration) throws Exception {
        if (columns.contains(column)) return;
        statement.execute("ALTER TABLE llm_growth ADD COLUMN " + column + " " + declaration);
    }
}
