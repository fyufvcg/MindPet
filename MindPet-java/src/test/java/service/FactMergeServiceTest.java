package service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FactMergeServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void keepsHomeAndCurrentLocationInSeparateSlots() {
        JdbcTemplate jdbc = database(tempDir.resolve("facts.db"));
        MemoryFactService facts = new MemoryFactService(jdbc);
        ProfileProjectionService profiles = new ProfileProjectionService(jdbc);

        var home = facts.merge("u", candidate("home_location", "宜兴", "turn-1"));
        var current = facts.merge("u", candidate("current_location", "南京", "turn-2"));
        assertThat(profiles.projectFact("u", home.id())).isTrue();
        assertThat(profiles.projectFact("u", current.id())).isTrue();

        assertThat(profiles.list("u"))
            .extracting(row -> row.get("slot_key") + "=" + row.get("value"))
            .containsExactly("current_location=南京", "home_location=宜兴");
    }

    @Test
    void supersedesOnlyTheSameCurrentSlot() {
        JdbcTemplate jdbc = database(tempDir.resolve("supersede.db"));
        MemoryFactService facts = new MemoryFactService(jdbc);
        ProfileProjectionService profiles = new ProfileProjectionService(jdbc);

        var first = facts.merge("u", candidate("current_location", "宜兴", "turn-1"));
        profiles.projectFact("u", first.id());
        var second = facts.merge("u", candidate("current_location", "南京", "turn-2"));
        profiles.projectFact("u", second.id());

        assertThat(jdbc.queryForObject("SELECT status FROM memory_fact WHERE id=?", String.class, first.id()))
            .isEqualTo("superseded");
        assertThat(jdbc.queryForObject("SELECT value FROM user_profile_current WHERE user_id=? AND slot_key=?",
            String.class, "u", "current_location")).isEqualTo("南京");
    }

    private MemoryFactService.FactCandidate candidate(String predicate, String value, String source) {
        return new MemoryFactService.FactCandidate(predicate, value, "", "current", "observed", 0.98,
            "", "", "2026-09-22T02:00:00Z", "Asia/Shanghai", "", "", "", "unknown",
            "unresolved", source, value);
    }

    private JdbcTemplate database(Path file) {
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
        new ResourceDatabasePopulator(new ClassPathResource("db/sqlite-schema.sql")).execute(source);
        return new JdbcTemplate(source);
    }
}
