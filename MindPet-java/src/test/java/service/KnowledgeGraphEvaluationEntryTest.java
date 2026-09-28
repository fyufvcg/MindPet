package service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import util.Logger;

import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeGraphEvaluationEntryTest {

    @Test
    void productionCompletedTurnRemainsAsynchronous() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);
        DynamicChatClientFactory factory = mock(DynamicChatClientFactory.class);
        when(factory.isConfigured()).thenReturn(true);
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        Executor executor = submitted::set;
        KnowledgeGraphService service = new KnowledgeGraphService(
            jdbc, factory, mock(EmbeddingService.class), mock(VectorSearchService.class),
            mock(SqliteMemoryService.class), new ObjectMapper(), executor, mock(Logger.class));

        boolean accepted = service.onCompletedTurn(
            "user", "session", "message", "reply", "neutral", Instant.parse("2026-10-01T00:00:00Z"));

        assertThat(accepted).isTrue();
        assertThat(submitted.get()).isNotNull();
        verify(factory, never()).build();
    }
}
