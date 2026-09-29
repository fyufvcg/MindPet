package service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FactMergeServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void keepsHomeAndCurrentLocationInSeparateSlots() {
        JdbcTemplate jdbc = database(tempDir.resolve("facts.db"));
        MemoryFactService facts = new MemoryFactService(jdbc);
        ProfileProjectionService profiles = new ProfileProjectionService(jdbc);

        appendTurn(jdbc, "turn-1", "2026-09-23T02:00:00Z");
        appendTurn(jdbc, "turn-2", "2026-09-24T02:00:00Z");
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

        appendTurn(jdbc, "turn-1", "2026-09-23T02:00:00Z");
        appendTurn(jdbc, "turn-2", "2026-09-24T02:00:00Z");
        var first = facts.merge("u", candidate("current_location", "宜兴", "turn-1"));
        profiles.projectFact("u", first.id());
        var second = facts.merge("u", candidate("current_location", "南京", "turn-2"));
        profiles.projectFact("u", second.id());

        assertThat(jdbc.queryForObject("SELECT status FROM memory_fact WHERE id=?", String.class, first.id()))
            .isEqualTo("superseded");
        assertThat(jdbc.queryForObject("SELECT value FROM user_profile_current WHERE user_id=? AND slot_key=?",
            String.class, "u", "current_location")).isEqualTo("南京");
    }

    @Test
    void lateOlderEventDoesNotReplaceNewerCurrentLocation() {
        JdbcTemplate jdbc = database(tempDir.resolve("late-old.db"));
        MemoryFactService facts = new MemoryFactService(jdbc);
        ProfileProjectionService profiles = new ProfileProjectionService(jdbc);

        appendTurn(jdbc, "new", "2026-09-24T02:00:00Z");
        appendTurn(jdbc, "old", "2026-09-22T02:00:00Z");
        var newer = facts.merge("u", candidateAt("current_location", "南京", "new", "2026-09-24T02:00:00Z", "observed"));
        profiles.projectFact("u", newer.id());
        var lateOlder = facts.merge("u", candidateAt("current_location", "宜兴", "old", "2026-09-22T02:00:00Z", "observed"));
        profiles.projectFact("u", lateOlder.id());

        assertThat(jdbc.queryForObject("SELECT value FROM user_profile_current WHERE user_id=? AND slot_key=?",
            String.class, "u", "current_location")).isEqualTo("南京");
        assertThat(jdbc.queryForObject("SELECT status FROM memory_fact WHERE id=?", String.class, lateOlder.id()))
            .isEqualTo("superseded");
    }

    @Test
    void negationRemovesTheMatchingCurrentProfile() {
        JdbcTemplate jdbc = database(tempDir.resolve("negation.db"));
        MemoryFactService facts = new MemoryFactService(jdbc);
        ProfileProjectionService profiles = new ProfileProjectionService(jdbc);

        appendTurn(jdbc, "turn-1", "2026-09-22T02:00:00Z");
        appendTurn(jdbc, "turn-2", "2026-09-23T02:00:00Z");
        var positive = facts.merge("u", candidateAt("current_location", "广州", "turn-1", "2026-09-22T02:00:00Z", "observed"));
        profiles.projectFact("u", positive.id());
        var negative = facts.merge("u", candidateAt("current_location", "广州", "turn-2", "2026-09-23T02:00:00Z", "negated"));
        profiles.projectFact("u", negative.id());

        assertThat(jdbc.queryForObject("SELECT status FROM memory_fact WHERE id=?", String.class, positive.id()))
            .isEqualTo("inactive");
        assertThat(profiles.list("u")).isEmpty();
    }

    @Test
    void replayOfSupersededProposalIsIdempotent() {
        JdbcTemplate jdbc = database(tempDir.resolve("replay-superseded.db"));
        MemoryFactService facts = new MemoryFactService(jdbc);
        ProfileProjectionService profiles = new ProfileProjectionService(jdbc);

        appendTurn(jdbc, "turn-1", "2026-09-22T02:00:00Z");
        appendTurn(jdbc, "turn-2", "2026-09-23T02:00:00Z");
        var first = facts.merge("u", candidateAt("current_location", "宜兴", "turn-1", "2026-09-22T02:00:00Z", "observed"));
        facts.merge("u", candidateAt("current_location", "南京", "turn-2", "2026-09-23T02:00:00Z", "observed"));
        var replay = facts.merge("u",
            candidateAt("current_location", "宜兴", "turn-1", "2026-09-22T02:00:00Z", "observed"));
        profiles.projectFact("u", replay.id());

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memory_fact WHERE user_id=?", Integer.class, "u"))
            .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT value FROM user_profile_current WHERE user_id=? AND slot_key=?",
            String.class, "u", "current_location")).isEqualTo("南京");
    }

    private MemoryFactService.FactCandidate candidate(String predicate, String value, String source) {
        String scope = "home_location".equals(predicate) ? "stable" : "current";
        long sequence = source.endsWith("-2") ? 2 : 1;
        Instant observedAt = Instant.parse("2026-09-22T02:00:00Z").plusSeconds(sequence * 86_400L);
        return new MemoryFactService.FactCandidate(predicate, value, "", scope, "observed", 0.98,
            "", "", observedAt.toString(), "Asia/Shanghai", "", "", "", "unknown",
            "unresolved", source, value, sequence);
    }

    private MemoryFactService.FactCandidate candidateAt(String predicate, String value, String source,
                                                         String occurredAt, String assertion) {
        String scope = "home_location".equals(predicate) ? "stable" : "current";
        long sequence = source.endsWith("-2") || "new".equals(source) ? 2 : 1;
        return new MemoryFactService.FactCandidate(predicate, value, "", scope, assertion, 0.98,
            "", "", occurredAt, "Asia/Shanghai", "", "", "", "unknown",
            "unresolved", source, value, sequence);
    }

    private void appendTurn(JdbcTemplate jdbc, String turnId, String occurredAt) {
        jdbc.update("INSERT INTO curator_turns(user_id,turn_id,completed_at,occurred_at,event_timezone) "
                + "VALUES(?,?,?,?,?)", "u", turnId, occurredAt, occurredAt, "Asia/Shanghai");
    }

    private JdbcTemplate database(Path file) {
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
        new ResourceDatabasePopulator(new ClassPathResource("db/sqlite-schema.sql")).execute(source);
        return new JdbcTemplate(source);
    }
}
