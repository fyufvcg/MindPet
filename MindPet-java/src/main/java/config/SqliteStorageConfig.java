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
        return dataSource;
    }
}
