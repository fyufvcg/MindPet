package service;

import com.fasterxml.jackson.databind.ObjectMapper;
import model.EvaluationWriteTrace;
import model.FormalEvaluationContract;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import util.Logger;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeGraphFormalReplayTest {

    @Test
    void variantsChangeOnlyTheirFrozenDecisionOrWhitelistGate() {
        KnowledgeGraphService service = service(mock(DynamicChatClientFactory.class));
        FormalEvaluationContract.ExtractionSnapshot lowConfidence = snapshot(
            true, 0.8, 0.2, List.of(
                relation("uses", true, true),
                relation("owns", false, true),
                relation("plans", true, false)));

        assertThat(policy(service, lowConfidence, "A0_DIRECT_SAVE_ALL").ltmDecision()).isTrue();
        assertThat(policy(service, lowConfidence, "A1_LLM_DECISION_ONLY").ltmDecision()).isTrue();
        assertThat(policy(service, lowConfidence, "B0_CURRENT_FULL").ltmDecision()).isFalse();
        assertThat(policy(service, lowConfidence, "B2_NO_LTM_CONFIDENCE_GATE").ltmDecision()).isTrue();

        KnowledgeGraphService.ReplayPolicy b0 = policy(service, lowConfidence, "B0_CURRENT_FULL");
        KnowledgeGraphService.ReplayPolicy b1 = policy(
            service, lowConfidence, "B1_NO_PREDICATE_WHITELIST");
        assertThat(b0.relations()).extracting(FormalEvaluationContract.RelationCandidate::predicate)
            .containsExactly("uses");
        assertThat(b0.predicateWhitelistRejectedCount()).isEqualTo(1);
        assertThat(b0.relationConfidenceRejectedCount()).isEqualTo(1);
        assertThat(b1.relations()).extracting(FormalEvaluationContract.RelationCandidate::predicate)
            .containsExactly("uses", "owns");
        assertThat(b1.predicateWhitelistRejectedCount()).isZero();
        assertThat(b1.relationConfidenceRejectedCount()).isEqualTo(1);
    }

    @Test
    void b2KeepsCombinedDecisionAndImportanceGate() {
        KnowledgeGraphService service = service(mock(DynamicChatClientFactory.class));
        assertThat(policy(service, snapshot(false, .9, .1, List.of()),
            "B2_NO_LTM_CONFIDENCE_GATE").ltmDecision()).isFalse();
        assertThat(policy(service, snapshot(true, .2, .9, List.of()),
            "B2_NO_LTM_CONFIDENCE_GATE").ltmDecision()).isFalse();
        assertThat(policy(service, snapshot(true, .35, .1, List.of()),
            "B2_NO_LTM_CONFIDENCE_GATE").ltmDecision()).isTrue();
    }

    @Test
    void replayUsesSnapshotWithoutBuildingOrCallingAnLlmClient() throws Exception {
        DynamicChatClientFactory chatFactory = mock(DynamicChatClientFactory.class);
        when(chatFactory.effectiveModel()).thenReturn("deepseek-flash");
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);
        when(jdbc.update(anyString(), org.mockito.ArgumentMatchers.<Object[]>any())).thenReturn(1);
        SqliteMemoryService memory = mock(SqliteMemoryService.class);
        KnowledgeGraphService service = new KnowledgeGraphService(
            jdbc, chatFactory, mock(EmbeddingService.class), mock(VectorSearchService.class),
            memory, new ObjectMapper(), Runnable::run, mock(Logger.class));
        Instant occurredAt = Instant.parse("2026-10-01T00:00:00Z");
        FormalEvaluationContract.ExtractionSnapshot unsigned = snapshot(false, .2, .9, List.of());
        unsigned = new FormalEvaluationContract.ExtractionSnapshot(
            unsigned.schemaVersion(), service.extractionPromptSha256(), "deepseek-flash",
            sha256(service, "hello\ncontext\n" + occurredAt), unsigned.rawWorthRemembering(),
            unsigned.rawMemoryShouldRemember(), unsigned.combinedShouldRemember(),
            unsigned.importance(), unsigned.confidence(), unsigned.evidence(), unsigned.entities(),
            unsigned.relations(), unsigned.parse(), unsigned.kgFilter(), unsigned.temporal(), null);
        FormalEvaluationContract.ExtractionSnapshot signed = sign(service, unsigned);

        KnowledgeGraphService.CompletedTurnResult result = service.replayForEvaluation(
            "e2e_memory_eval_user", "e2e:run:f0001", "hello", "context", "neutral",
            occurredAt, FormalEvaluationContract.Variant.B0_CURRENT_FULL, signed);

        assertThat(result.extractionCompleted()).isTrue();
        assertThat(result.ltmAttempted()).isFalse();
        verify(chatFactory, never()).build();
        verify(memory, never()).appendTurn(
            anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyDouble(),
            org.mockito.ArgumentMatchers.anyDouble(), anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void invalidSnapshotFailsBeforePersistenceOrLlmConstruction() {
        DynamicChatClientFactory chatFactory = mock(DynamicChatClientFactory.class);
        when(chatFactory.effectiveModel()).thenReturn("deepseek-flash");
        SqliteMemoryService memory = mock(SqliteMemoryService.class);
        KnowledgeGraphService service = new KnowledgeGraphService(
            mock(JdbcTemplate.class), chatFactory, mock(EmbeddingService.class),
            mock(VectorSearchService.class), memory, new ObjectMapper(), Runnable::run,
            mock(Logger.class));

        KnowledgeGraphService.CompletedTurnFailure failure = assertThrows(
            KnowledgeGraphService.CompletedTurnFailure.class,
            () -> service.replayForEvaluation(
                "e2e_memory_eval_user", "e2e:run:f0001", "hello", "context", "neutral",
                Instant.parse("2026-10-01T00:00:00Z"),
                FormalEvaluationContract.Variant.A0_DIRECT_SAVE_ALL,
                snapshot(false, .2, .2, List.of())));

        assertThat(failure.type()).isEqualTo("SNAPSHOT_CONTRACT_INVALID");
        verify(chatFactory, never()).build();
        verify(memory, never()).appendTurn(
            anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyDouble(),
            org.mockito.ArgumentMatchers.anyDouble(), anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void normalProductionEntryPointsExposeNoVariantParameter() {
        assertThat(Arrays.stream(KnowledgeGraphService.class.getMethods())
            .filter(method -> method.getName().equals("onCompletedTurn"))
            .flatMap(method -> Arrays.stream(method.getParameterTypes())))
            .doesNotContain(FormalEvaluationContract.Variant.class);
    }

    private KnowledgeGraphService.ReplayPolicy policy(
            KnowledgeGraphService service,
            FormalEvaluationContract.ExtractionSnapshot snapshot,
            String variant) {
        return service.evaluationReplayPolicy(
            snapshot, FormalEvaluationContract.Variant.valueOf(variant));
    }

    private FormalEvaluationContract.RelationCandidate relation(
            String predicate, boolean allowed, boolean confidencePassed) {
        return new FormalEvaluationContract.RelationCandidate(
            "user", "target", predicate, confidencePassed ? .9 : .4, .7,
            allowed, confidencePassed);
    }

    private FormalEvaluationContract.ExtractionSnapshot snapshot(
            boolean decision, double importance, double confidence,
            List<FormalEvaluationContract.RelationCandidate> relations) {
        return new FormalEvaluationContract.ExtractionSnapshot(
            "mindpet-exp1-extraction-v1", "prompt", "model", "source",
            decision, decision, decision, importance, confidence, "evidence", List.of(), relations,
            new EvaluationWriteTrace.ParseDiagnostics(
                true, false, false, false, false, false, null),
            new EvaluationWriteTrace.KgFilterTrace(
                0, relations.size(), 0, 0, 0, 0, 0, 0, 0, 0),
            new EvaluationWriteTrace.TemporalTrace(
                null, null, "UTC", "none", "2026-10-01T00:00:00Z", "UTC"),
            null);
    }

    private KnowledgeGraphService service(DynamicChatClientFactory chatFactory) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);
        return new KnowledgeGraphService(
            jdbc, chatFactory, mock(EmbeddingService.class), mock(VectorSearchService.class),
            mock(SqliteMemoryService.class), new ObjectMapper(), Runnable::run, mock(Logger.class));
    }

    private FormalEvaluationContract.ExtractionSnapshot sign(
            KnowledgeGraphService service,
            FormalEvaluationContract.ExtractionSnapshot snapshot) throws Exception {
        Method hash = KnowledgeGraphService.class.getDeclaredMethod(
            "snapshotHash", FormalEvaluationContract.ExtractionSnapshot.class);
        hash.setAccessible(true);
        String value = (String) hash.invoke(service, snapshot);
        Method signer = KnowledgeGraphService.class.getDeclaredMethod(
            "withSnapshotHash", FormalEvaluationContract.ExtractionSnapshot.class, String.class);
        signer.setAccessible(true);
        return (FormalEvaluationContract.ExtractionSnapshot) signer.invoke(service, snapshot, value);
    }

    private String sha256(KnowledgeGraphService service, String value) throws Exception {
        Method method = KnowledgeGraphService.class.getDeclaredMethod("sha256", String.class);
        method.setAccessible(true);
        return (String) method.invoke(service, value);
    }
}
