package service;

import model.E2eMemoryIngestResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class E2eMemoryEvaluationServiceTest {
    private JdbcTemplate jdbc;
    private KnowledgeGraphService knowledgeGraph;
    private E2eMemoryEvaluationService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        knowledgeGraph = mock(KnowledgeGraphService.class);
        service = new E2eMemoryEvaluationService(jdbc, knowledgeGraph, 5_000);
        when(jdbc.queryForObject("SELECT current_database()", String.class))
            .thenReturn(E2eMemoryEvaluationService.REQUIRED_DATABASE);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
            .thenReturn(0);
    }

    private KnowledgeGraphService.CompletedTurnResult completed(boolean ltmAttempted) {
        return new KnowledgeGraphService.CompletedTurnResult(
            "turn-hash", "production-model", false, true,
            true, true, .7, .9, ltmAttempted,
            true, false, false, false, false);
    }

    private E2eMemoryIngestResult ingest() {
        return service.ingest("p001", "pilot01", E2eMemoryEvaluationService.EVAL_USER,
            "我长期喜欢羽毛球", "明白了", "neutral", Instant.parse("2026-09-26T10:00:00Z"));
    }

    @Test
    void wrongDatabaseFailsClosedBeforeProductionPipeline() {
        when(jdbc.queryForObject("SELECT current_database()", String.class)).thenReturn("mindpet");

        E2eMemoryEvaluationService.EvaluationFailure failure = assertThrows(
            E2eMemoryEvaluationService.EvaluationFailure.class, this::ingest);

        assertEquals("WRONG_DATABASE", failure.type());
        verifyNoInteractions(knowledgeGraph);
    }

    @Test
    void wrongUserAndUnsafeSampleFailBeforeDatabaseOrPipeline() {
        assertEquals("USER_NOT_ALLOWED", assertThrows(
            E2eMemoryEvaluationService.EvaluationFailure.class,
            () -> service.ingest("p001", "pilot01", "desktop-user",
                "message", "context", "neutral", Instant.now())).type());
        assertEquals("INVALID_SAMPLE_ID", assertThrows(
            E2eMemoryEvaluationService.EvaluationFailure.class,
            () -> service.ingest("../p001", "pilot01", E2eMemoryEvaluationService.EVAL_USER,
                "message", "context", "neutral", Instant.now())).type());
        verifyNoInteractions(knowledgeGraph);
    }

    @Test
    @SuppressWarnings("unchecked")
    void waitsForRealCompletionFutureBeforeReadingPersistence() throws Exception {
        CountDownLatch waitingOnCompletion = new CountDownLatch(1);
        CompletableFuture<KnowledgeGraphService.CompletedTurnResult> future = new CompletableFuture<>() {
            @Override
            public KnowledgeGraphService.CompletedTurnResult get(long timeout, TimeUnit unit)
                    throws InterruptedException, java.util.concurrent.ExecutionException,
                    java.util.concurrent.TimeoutException {
                waitingOnCompletion.countDown();
                return super.get(timeout, unit);
            }
        };
        when(knowledgeGraph.onCompletedTurnForEvaluation(anyString(), anyString(), anyString(),
            anyString(), anyString(), any())).thenReturn(future);
        when(jdbc.queryForObject(contains("kg_turn_ingest WHERE user_id"),
            eq(Integer.class), any(Object[].class))).thenReturn(1);
        AtomicReference<E2eMemoryIngestResult> result = new AtomicReference<>();
        Thread worker = new Thread(() -> result.set(ingest()));
        worker.start();
        assertTrue(waitingOnCompletion.await(1, TimeUnit.SECONDS));
        assertNull(result.get(), "ingest must not return before the production completion signal");

        future.complete(completed(false));
        worker.join(2_000);

        assertNotNull(result.get());
        assertEquals("NO_PERSIST", result.get().status());
        assertTrue(result.get().turnIngestRecorded());
    }

    @Test
    @SuppressWarnings("unchecked")
    void observesFullSuccessAndReturnsTraceableRowIds() {
        when(knowledgeGraph.onCompletedTurnForEvaluation(anyString(), anyString(), anyString(),
            anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(completed(true)));
        when(jdbc.query(startsWith("SELECT id::text FROM long_term_memory WHERE user_id=? AND session_id=?"),
            any(RowMapper.class), any(Object[].class)))
            .thenReturn(List.of(), List.of("101"));
        when(jdbc.query(startsWith("SELECT DISTINCT entity_id"),
            any(RowMapper.class), any(Object[].class))).thenReturn(List.of("e1"));
        when(jdbc.query(startsWith("SELECT DISTINCT relation_id"),
            any(RowMapper.class), any(Object[].class))).thenReturn(List.of("r1"));
        when(jdbc.query(startsWith("SELECT id::text FROM kg_evidence"),
            any(RowMapper.class), any(Object[].class))).thenReturn(List.of("201", "202"));
        when(jdbc.queryForObject(contains("kg_turn_ingest WHERE user_id"),
            eq(Integer.class), any(Object[].class))).thenReturn(1);

        E2eMemoryIngestResult result = ingest();

        assertEquals("FULL_SUCCESS", result.status());
        assertTrue(result.ltmAttempted());
        assertTrue(result.ltmPersisted());
        assertEquals(List.of("101"), result.rows().longTermMemoryIds());
        assertEquals(List.of("e1"), result.rows().entityIds());
        assertEquals(1, result.entityRowsCreatedOrUpdated());
        assertEquals(1, result.relationRowsCreatedOrUpdated());
        assertEquals(2, result.evidenceRowsCreated());
    }

    @Test
    void attemptedLtmWithoutTraceableRowIsPartialSuccess() {
        when(knowledgeGraph.onCompletedTurnForEvaluation(anyString(), anyString(), anyString(),
            anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(completed(true)));
        when(jdbc.queryForObject(contains("kg_turn_ingest WHERE user_id"),
            eq(Integer.class), any(Object[].class))).thenReturn(1);

        E2eMemoryIngestResult result = ingest();

        assertEquals("KG_ONLY_PARTIAL_SUCCESS", result.status());
        assertEquals("LTM_PERSISTENCE", result.errorStage());
        assertEquals("EXPECTED_ROW_NOT_FOUND", result.errorType());
    }

    @Test
    @SuppressWarnings("unchecked")
    void observesPrunedIdsEvenWhenTheTotalRowCountDoesNotChange() {
        when(knowledgeGraph.onCompletedTurnForEvaluation(anyString(), anyString(), anyString(),
            anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(completed(false)));
        when(jdbc.query(eq("SELECT id::text FROM long_term_memory WHERE user_id=? ORDER BY id"),
            any(RowMapper.class), any(Object[].class)))
            .thenReturn(List.of("old-1", "old-2"), List.of("old-2", "new-1"));
        when(jdbc.queryForObject(contains("kg_turn_ingest WHERE user_id"),
            eq(Integer.class), any(Object[].class))).thenReturn(1);

        E2eMemoryIngestResult result = ingest();

        assertEquals(2, result.ltmRowsBefore());
        assertEquals(2, result.ltmRowsAfter());
        assertTrue(result.pruneOccurred());
        assertEquals(1, result.pruneDeletedEstimate());
    }

    @Test
    void resetRejectsWrongDatabaseAndDeletesOnlyFixedUserInFkOrder() {
        when(jdbc.queryForObject("SELECT current_database()", String.class)).thenReturn("mindpet_eval");
        assertEquals("WRONG_DATABASE", assertThrows(
            E2eMemoryEvaluationService.EvaluationFailure.class,
            () -> service.reset(E2eMemoryEvaluationService.EVAL_USER)).type());
        verify(jdbc, never()).update(startsWith("DELETE"), any(Object[].class));

        reset(jdbc);
        knowledgeGraph = mock(KnowledgeGraphService.class);
        service = new E2eMemoryEvaluationService(jdbc, knowledgeGraph, 5_000);
        when(jdbc.queryForObject("SELECT current_database()", String.class))
            .thenReturn(E2eMemoryEvaluationService.REQUIRED_DATABASE);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
        when(jdbc.update(startsWith("DELETE"), eq(E2eMemoryEvaluationService.EVAL_USER))).thenReturn(1);

        E2eMemoryIngestResult.ResetResult result = service.reset(E2eMemoryEvaluationService.EVAL_USER);

        assertEquals("OK", result.status());
        var order = inOrder(jdbc);
        order.verify(jdbc).update("DELETE FROM kg_evidence WHERE user_id=?", E2eMemoryEvaluationService.EVAL_USER);
        order.verify(jdbc).update("DELETE FROM kg_relation WHERE user_id=?", E2eMemoryEvaluationService.EVAL_USER);
        order.verify(jdbc).update("DELETE FROM kg_entity WHERE user_id=?", E2eMemoryEvaluationService.EVAL_USER);
        order.verify(jdbc).update("DELETE FROM kg_turn_ingest WHERE user_id=?", E2eMemoryEvaluationService.EVAL_USER);
        order.verify(jdbc).update("DELETE FROM long_term_memory WHERE user_id=?", E2eMemoryEvaluationService.EVAL_USER);
    }
}
