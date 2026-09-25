package service;

import com.fasterxml.jackson.databind.ObjectMapper;
import model.ImportanceScoreResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.core.JdbcTemplate;
import util.Logger;

import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KnowledgeGraphImportanceEvaluationTest {
    private JdbcTemplate jdbc;
    private DynamicChatClientFactory factory;
    private EmbeddingService embeddingService;
    private PgVectorMemoryService memoryService;
    private KnowledgeGraphService service;
    private ChatClient client;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        factory = mock(DynamicChatClientFactory.class);
        embeddingService = mock(EmbeddingService.class);
        memoryService = mock(PgVectorMemoryService.class);
        Logger logger = mock(Logger.class);
        Executor direct = Runnable::run;
        service = new KnowledgeGraphService(jdbc, factory, embeddingService, memoryService,
            new ObjectMapper(), direct, logger);
        clearInvocations(jdbc, embeddingService, memoryService, logger);
        client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(factory.isConfigured()).thenReturn(true);
        when(factory.effectiveModel()).thenReturn("production-model");
        when(factory.build()).thenReturn(client);
        when(factory.applyCurrentModel(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void modelReturns(String response) {
        when(client.prompt().system(any(String.class)).user(any(String.class))
            .call().content()).thenReturn(response);
    }

    @Test
    void evaluationUsesProductionModelParsingClampAndNeverWrites() {
        modelReturns("""
            {"worthRemembering":true,"memory":{"shouldRemember":true,
             "importance":1.4,"confidence":-0.2,"evidence":"explicit"},
             "entities":[],"relations":[]}
            """);

        ImportanceScoreResult result = service.scoreImportanceForEvaluation(
            "我长期喜欢羽毛球", "明白了");

        assertEquals("OK", result.status());
        assertEquals("production-model", result.model());
        assertTrue(result.worthRemembering());
        assertTrue(result.shouldRemember());
        assertEquals(1.0, result.importance());
        assertEquals(0.0, result.confidence());
        assertTrue(result.highImportance());
        assertFalse(result.wouldPersistMemory());
        assertTrue(result.parse().succeeded());
        assertTrue(result.parse().memoryObjectPresent());
        assertTrue(result.parse().importanceClamped());
        assertTrue(result.parse().confidenceClamped());
        assertFalse(result.parse().importanceFallbackUsed());
        assertFalse(result.parse().confidenceFallbackUsed());
        verify(factory).build();
        verify(factory).applyCurrentModel(any());
        verifyNoInteractions(jdbc, embeddingService, memoryService);
    }

    @Test
    void missingMemoryUsesTheSameCandidateFallbacksAsProduction() {
        modelReturns("""
            {"worthRemembering":true,
             "entities":[{"name":"羽毛球","type":"preference",
               "summary":"用户喜欢羽毛球","importance":0.7}],"relations":[]}
            """);

        ImportanceScoreResult result = service.scoreImportanceForEvaluation(
            "我一直喜欢羽毛球", "");

        assertTrue(result.shouldRemember());
        assertEquals(0.7, result.importance(), 1e-12);
        assertEquals(0.8, result.confidence(), 1e-12);
        assertTrue(result.wouldPersistMemory());
        assertFalse(result.parse().memoryObjectPresent());
        assertTrue(result.parse().importanceFallbackUsed());
        assertTrue(result.parse().confidenceFallbackUsed());
        verifyNoInteractions(jdbc, embeddingService, memoryService);
    }

    @Test
    void malformedModelResponseIsReportedWithoutPersistence() {
        modelReturns("not json");

        KnowledgeGraphService.ImportanceEvaluationFailure failure = assertThrows(
            KnowledgeGraphService.ImportanceEvaluationFailure.class,
            () -> service.scoreImportanceForEvaluation("我长期学习英语", "")
        );

        assertEquals("MODEL_RESPONSE_INVALID", failure.code());
        verifyNoInteractions(jdbc, embeddingService, memoryService);
    }

    @Test
    void unconfiguredModelFailsClosedBeforeAnyModelOrDatabaseCall() {
        when(factory.isConfigured()).thenReturn(false);

        KnowledgeGraphService.ImportanceEvaluationFailure failure = assertThrows(
            KnowledgeGraphService.ImportanceEvaluationFailure.class,
            () -> service.scoreImportanceForEvaluation("我长期学习英语", "")
        );

        assertEquals("MODEL_NOT_CONFIGURED", failure.code());
        verify(factory, never()).build();
        verifyNoInteractions(jdbc, embeddingService, memoryService);
    }
}
