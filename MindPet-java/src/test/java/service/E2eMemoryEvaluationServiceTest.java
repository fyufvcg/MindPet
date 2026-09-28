package service;

import config.EvaluationSqlitePathGuard;
import config.SqliteStorageConfig;
import model.E2eMemoryIngestResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class E2eMemoryEvaluationServiceTest {
    @TempDir
    Path tempDir;

    private JdbcTemplate jdbc;
    private KnowledgeGraphService knowledgeGraph;
    private Path database;
    private E2eMemoryEvaluationService service;

    @BeforeEach
    void setUp() {
        database = tempDir.resolve("eval-data").resolve("test-run").resolve("mindpet-e2e.db");
        database.toFile().getParentFile().mkdirs();
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + database.toAbsolutePath());
        new ResourceDatabasePopulator(new ClassPathResource("db/sqlite-schema.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        knowledgeGraph = mock(KnowledgeGraphService.class);
        when(knowledgeGraph.extractionPromptSha256()).thenReturn("prompt-hash");
        when(knowledgeGraph.effectiveModelForEvaluation()).thenReturn("test-model");
        service = service(database, database, tempDir.resolve("eval-data"), tempDir.resolve("home"));
    }

    @Test
    void temporarySqliteSchemaInitializesAllFiveEvaluationTables() {
        for (String table : List.of(
                "long_term_memory", "kg_entity", "kg_relation", "kg_evidence", "kg_turn_ingest")) {
            Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?", Integer.class, table);
            assertThat(count).isEqualTo(1);
        }
    }

    @Test
    void pathMismatchFailsClosedBeforeDatabaseOrPipeline() {
        E2eMemoryEvaluationService guarded = service(
            tempDir.resolve("other.db"), database, tempDir.resolve("eval-data"), tempDir.resolve("home"));

        E2eMemoryEvaluationService.EvaluationFailure failure = assertThrows(
            E2eMemoryEvaluationService.EvaluationFailure.class, guarded::snapshot);

        assertThat(failure.type()).isEqualTo("SQLITE_PATH_MISMATCH");
        verifyNoInteractions(knowledgeGraph);
    }

    @Test
    void pathOutsideAllowedRootFailsClosed() {
        Path outside = tempDir.resolve("outside").resolve("mindpet-e2e.db");
        E2eMemoryEvaluationService guarded = service(
            outside, outside, tempDir.resolve("eval-data"), tempDir.resolve("home"));

        E2eMemoryEvaluationService.EvaluationFailure failure = assertThrows(
            E2eMemoryEvaluationService.EvaluationFailure.class, guarded::snapshot);

        assertThat(failure.type()).isEqualTo("SQLITE_PATH_OUTSIDE_ALLOWED_ROOT");
        verifyNoInteractions(knowledgeGraph);
    }

    @Test
    void defaultProductionPathFailsClosedWithoutOpeningIt() {
        Path fakeHome = tempDir.resolve("home");
        Path productionDefault = fakeHome.resolve(".mindpet").resolve("mindpet.db");
        E2eMemoryEvaluationService guarded = service(
            productionDefault, productionDefault, fakeHome, fakeHome);

        E2eMemoryEvaluationService.EvaluationFailure failure = assertThrows(
            E2eMemoryEvaluationService.EvaluationFailure.class, guarded::snapshot);

        assertThat(failure.type()).isEqualTo("PRODUCTION_SQLITE_PATH_FORBIDDEN");
        verifyNoInteractions(knowledgeGraph);
    }

    @Test
    void datasourceRejectsProductionDefaultBeforeCreatingOrOpeningIt() {
        Path unopenedHome = tempDir.resolve("unopened-home");
        Path productionDefault = unopenedHome.resolve(".mindpet").resolve("mindpet.db");
        SqliteStorageConfig config = new SqliteStorageConfig();

        EvaluationSqlitePathGuard.GuardFailure failure = assertThrows(
            EvaluationSqlitePathGuard.GuardFailure.class,
            () -> config.sqliteDataSource(
                productionDefault.toString(), "", true, productionDefault.toString(),
                unopenedHome.toString(), unopenedHome.toString()));

        assertThat(failure.type()).isEqualTo("PRODUCTION_SQLITE_PATH_FORBIDDEN");
        assertThat(productionDefault.getParent()).doesNotExist();
    }

    @Test
    void snapshotReturnsOnlyFixedEvaluationUserRows() {
        seedBothUsers();

        E2eMemoryIngestResult.Snapshot snapshot = service.snapshot();

        assertThat(snapshot.userId()).isEqualTo(E2eMemoryEvaluationService.EVAL_USER);
        assertThat(snapshot.databasePath()).isEqualTo(database.toAbsolutePath().normalize().toString());
        assertThat(snapshot.tables().get("long_term_memory").count()).isEqualTo(1);
        assertThat(snapshot.tables().get("kg_entity").count()).isEqualTo(2);
        assertThat(snapshot.tables().get("kg_relation").count()).isEqualTo(1);
        assertThat(snapshot.tables().get("kg_evidence").count()).isEqualTo(1);
        assertThat(snapshot.tables().get("kg_turn_ingest").count()).isEqualTo(1);
        assertThat(snapshot.tables().values()).allSatisfy(table ->
            assertThat(table.rows()).allSatisfy(row ->
                assertThat(row.values()).doesNotContain("desktop-user")));
    }

    @Test
    void resetDeletesOnlyFixedUserInForeignKeySafeOrder() {
        seedBothUsers();

        E2eMemoryIngestResult.ResetResult result = service.reset();

        assertThat(result.after().tables().values()).allSatisfy(table -> assertThat(table.count()).isZero());
        for (String table : List.of(
                "long_term_memory", "kg_entity", "kg_relation", "kg_evidence", "kg_turn_ingest")) {
            Integer otherRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE user_id=?", Integer.class, "desktop-user");
            assertThat(otherRows).isPositive();
        }
    }

    @Test
    void ingestWaitsForProductionCompletionAndForcesFixedUser() throws Exception {
        CompletableFuture<KnowledgeGraphService.CompletedTurnResult> completion = new CompletableFuture<>();
        when(knowledgeGraph.onCompletedTurnForEvaluation(
            anyString(), anyString(), anyString(), anyString(), anyString(), any())).thenReturn(completion);
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<E2eMemoryIngestResult> result = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            started.countDown();
            result.set(service.ingest(
                "p001", "pilot01", "hello", "context", "neutral", Instant.parse("2026-10-01T00:00:00Z")));
        });
        worker.start();
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(50);
        assertThat(result.get()).isNull();

        String turnHash = "turn-hash";
        jdbc.update(
            "INSERT INTO kg_turn_ingest(turn_hash,user_id,session_id,entity_count,relation_count) VALUES(?,?,?,?,?)",
            turnHash, E2eMemoryEvaluationService.EVAL_USER, "e2e:pilot01:p001", 0, 0);
        completion.complete(new KnowledgeGraphService.CompletedTurnResult(
            turnHash, "test-model", false, true, false, 0.2, 0.9, false));
        worker.join(2_000);

        assertThat(result.get()).isNotNull();
        assertThat(result.get().status()).isEqualTo("NO_PERSIST");
        assertThat(result.get().userId()).isEqualTo(E2eMemoryEvaluationService.EVAL_USER);
        assertThat(result.get().turnIngestRecorded()).isTrue();
    }

    private E2eMemoryEvaluationService service(
            Path storage, Path evaluation, Path root, Path home) {
        return new E2eMemoryEvaluationService(
            jdbc, knowledgeGraph, storage.toString(), evaluation.toString(),
            root.toString(), home.toString(), 5_000);
    }

    private void seedBothUsers() {
        seedUser(E2eMemoryEvaluationService.EVAL_USER, "eval");
        seedUser("desktop-user", "other");
    }

    private void seedUser(String userId, String prefix) {
        jdbc.update(
            "INSERT INTO long_term_memory(user_id,session_id,content,role) VALUES(?,?,?,?)",
            userId, prefix + "-session", prefix + " memory", "user");
        jdbc.update(
            "INSERT INTO kg_entity(id,user_id,normalized_name,display_name,entity_type) VALUES(?,?,?,?,?)",
            prefix + "-source", userId, prefix + " source", prefix + " source", "person");
        jdbc.update(
            "INSERT INTO kg_entity(id,user_id,normalized_name,display_name,entity_type) VALUES(?,?,?,?,?)",
            prefix + "-target", userId, prefix + " target", prefix + " target", "topic");
        jdbc.update(
            "INSERT INTO kg_relation(id,user_id,source_entity_id,target_entity_id,predicate) VALUES(?,?,?,?,?)",
            prefix + "-relation", userId, prefix + "-source", prefix + "-target", "related_to");
        jdbc.update(
            "INSERT INTO kg_evidence(user_id,turn_hash,relation_id,session_id,user_message) VALUES(?,?,?,?,?)",
            userId, prefix + "-hash", prefix + "-relation", prefix + "-session", prefix + " message");
        jdbc.update(
            "INSERT INTO kg_turn_ingest(turn_hash,user_id,session_id,entity_count,relation_count) VALUES(?,?,?,?,?)",
            prefix + "-hash", userId, prefix + "-session", 2, 1);
    }
}
