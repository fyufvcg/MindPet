package service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import util.Logger;

import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeGraphObservationTraceTest {

    @Test
    void parserDiagnosticsAndFilterCountsReflectTheProductionParser() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);
        KnowledgeGraphService service = new KnowledgeGraphService(
            jdbc, mock(DynamicChatClientFactory.class), mock(EmbeddingService.class),
            mock(VectorSearchService.class), mock(SqliteMemoryService.class),
            new ObjectMapper(), Runnable::run, mock(Logger.class));
        String response = """
            {
              "worthRemembering": true,
              "memory": {"shouldRemember": true, "importance": 0.8},
              "entities": [
                {"name":"Project Alpha","type":"project","summary":"ongoing","importance":0.8},
                {"name":"API key token","type":"tool","summary":"secret","importance":0.9}
              ],
              "relations": [
                {"source":"user","target":"Project Alpha","predicate":"works_on","confidence":0.9,"importance":0.8},
                {"source":"user","target":"Project Alpha","predicate":"owns","confidence":0.9,"importance":0.8},
                {"source":"user","target":"Project Alpha","predicate":"plans","confidence":0.4,"importance":0.8}
              ]
            }
            """;

        Method parser = KnowledgeGraphService.class.getDeclaredMethod("parseExtraction", String.class);
        parser.setAccessible(true);
        Object extraction = parser.invoke(service, response);
        KnowledgeGraphService.ParseDiagnostics parse = (KnowledgeGraphService.ParseDiagnostics)
            accessor(extraction, "parseDiagnostics");
        KnowledgeGraphService.KgFilterDiagnostics filters = (KnowledgeGraphService.KgFilterDiagnostics)
            accessor(extraction, "kgFilterDiagnostics");

        assertThat(parse.memoryObjectPresent()).isTrue();
        assertThat(parse.importanceFallbackUsed()).isFalse();
        assertThat(parse.confidenceFallbackUsed()).isTrue();
        assertThat(parse.importanceClamped()).isFalse();
        assertThat(parse.confidenceClamped()).isFalse();
        assertThat(parse.parseFailure()).isFalse();
        assertThat(filters.rawEntityCount()).isEqualTo(2);
        assertThat(filters.normalizedEntityCount()).isEqualTo(1);
        assertThat(filters.sensitivityRejectedEntityCount()).isEqualTo(1);
        assertThat(filters.rawRelationCount()).isEqualTo(3);
        assertThat(filters.normalizedRelationCount()).isEqualTo(1);
        assertThat(filters.predicateWhitelistRejectedCount()).isEqualTo(1);
        assertThat(filters.relationConfidenceRejectedCount()).isEqualTo(1);
    }

    @Test
    void temporalDiagnosticsExposeExactlyTheProductionResolverOutput() {
        Instant reference = Instant.parse("2026-10-01T00:00:00Z");
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        TemporalMemory.Resolved resolved = TemporalMemory.resolve("明天下午3点开会", reference, zone);

        KnowledgeGraphService.TemporalDiagnostics trace =
            KnowledgeGraphService.temporalDiagnostics("明天下午3点开会", reference, zone);

        assertThat(trace.eventDate()).isEqualTo(resolved.eventDate().toString());
        assertThat(trace.eventAt()).isEqualTo(resolved.eventAt().toString());
        assertThat(trace.eventTimezone()).isEqualTo(resolved.timezone());
        assertThat(trace.eventPrecision()).isEqualTo(resolved.precision());
        assertThat(trace.referenceTimestamp()).isEqualTo(reference.toString());
        assertThat(trace.referenceTimezone()).isEqualTo(zone.getId());
    }

    @Test
    void malformedExtractionIsClassifiedAsAParseFailureWithoutExposingContent() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);
        KnowledgeGraphService service = new KnowledgeGraphService(
            jdbc, mock(DynamicChatClientFactory.class), mock(EmbeddingService.class),
            mock(VectorSearchService.class), mock(SqliteMemoryService.class),
            new ObjectMapper(), Runnable::run, mock(Logger.class));
        Method parser = KnowledgeGraphService.class.getDeclaredMethod("parseExtraction", String.class);
        parser.setAccessible(true);

        InvocationTargetException failure = assertThrows(
            InvocationTargetException.class, () -> parser.invoke(service, "not json"));

        assertThat(failure.getCause().getClass().getSimpleName()).isEqualTo("ExtractionParseFailure");
        assertThat(failure.getCause().getMessage()).isEqualTo("INVALID_EXTRACTION_RESPONSE");
        assertThat(failure.getCause().getMessage()).doesNotContain("not json");
    }

    private Object accessor(Object target, String method) throws Exception {
        Method accessor = target.getClass().getDeclaredMethod(method);
        accessor.setAccessible(true);
        return accessor.invoke(target);
    }
}
