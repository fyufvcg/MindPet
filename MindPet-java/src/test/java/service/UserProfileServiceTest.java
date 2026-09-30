package service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class UserProfileServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void readsExplicitUserTimezoneFromProfile() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("profile.db").toAbsolutePath());
        new ResourceDatabasePopulator(new ClassPathResource("db/sqlite-schema.sql")).execute(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        UserProfileService profiles = new UserProfileService(jdbc, new ProfileProjectionService(jdbc));

        assertThat(profiles.getConfiguredTimezone("u")).isNull();
        profiles.save("u", "preference", "timezone", "America/Los_Angeles");

        assertThat(profiles.getConfiguredTimezone("u")).isEqualTo("America/Los_Angeles");
    }
}
