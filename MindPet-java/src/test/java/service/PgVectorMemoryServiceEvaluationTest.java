package service;

import model.RetrievalDebugResult;
import model.RetrievalMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import util.Logger;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Pure mocks: no Spring application, database, or real embedding server is started. */
class PgVectorMemoryServiceEvaluationTest {
    private static final LocalDateTime EVALUATION_AS_OF =
        LocalDateTime.parse("2026-09-21T08:38:06.750458");
    private JdbcTemplate jdbc;
    private EmbeddingService embedding;
    private PgVectorMemoryService service;
    private final List<String> sqlCalls = new ArrayList<>();
    private final List<PreparedStatement> statements = new ArrayList<>();
    private List<PgVectorMemoryService.MemoryResult> semantic;
    private List<PgVectorMemoryService.MemoryResult> keyword;
    private float[] vector;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        embedding = mock(EmbeddingService.class);
        service = new PgVectorMemoryService(jdbc, embedding, mock(Logger.class));
        // Existing constructor initialization touches mocks only; isolate request interactions.
        clearInvocations(jdbc, embedding);
        vector = new float[1024];
        vector[0] = 1;
        when(embedding.embed(anyString())).thenReturn(vector);
        Timestamp now = Timestamp.valueOf(EVALUATION_AS_OF.minusHours(1));
        var m1 = memory("1", "我最喜欢羽毛球", now, .3, .4, .9, "positive", 3);
        var m2 = memory("2", "喜欢毛球", now, .05, .95, 1, "neutral", 2);
        var m3 = memory("3", "喜欢篮球", now, .01, .1, .1, "negative", 3);
        semantic = List.of(m3, m2, m1); // SQL cosine-distance order.
        keyword = List.of(m2, m1, m3); // SQL importance order before Java matching.
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            PreparedStatementSetter setter = invocation.getArgument(1);
            PreparedStatement ps = mock(PreparedStatement.class);
            setter.setValues(ps);
            sqlCalls.add(sql);
            statements.add(ps);
            return sql.contains("embedding <=>") ? semantic : keyword;
        }).when(jdbc).query(anyString(), any(PreparedStatementSetter.class),
            org.mockito.ArgumentMatchers.<RowMapper<PgVectorMemoryService.MemoryResult>>any());
    }

    private static PgVectorMemoryService.MemoryResult memory(String id, String content, Timestamp at,
        double distance, double importance, double confidence, String emotion, int layer) {
        return new PgVectorMemoryService.MemoryResult(id, content, "user", at, null, null, null, null,
            distance, importance, confidence, emotion, layer, 1);
    }

    private RetrievalDebugResult evaluate(RetrievalMode mode, int k) {
        return evaluate(mode, k, EVALUATION_AS_OF);
    }

    private RetrievalDebugResult evaluate(RetrievalMode mode, int k, LocalDateTime asOf) {
        return service.searchForEvaluation("eval_test_user", "羽毛球", mode, k, asOf);
    }

    private List<String> ids(RetrievalDebugResult result) {
        return result.results().stream().map(RetrievalDebugResult.Entry::memoryId).toList();
    }

    private void assertSelectOnly(int count) {
        verify(jdbc, times(count)).query(anyString(), any(PreparedStatementSetter.class),
            org.mockito.ArgumentMatchers.<RowMapper<PgVectorMemoryService.MemoryResult>>any());
        verifyNoMoreInteractions(jdbc); // No execute/update/count or any other JDBC call.
        assertTrue(sqlCalls.stream().allMatch(sql -> sql.startsWith("SELECT ")));
        assertTrue(sqlCalls.stream().allMatch(sql -> sql.contains("?::timestamp")));
        assertTrue(sqlCalls.stream().noneMatch(sql -> sql.contains("NOW()")));
    }

    private boolean usesBothLanes(RetrievalMode mode) {
        return mode != RetrievalMode.KEYWORD_ONLY && mode != RetrievalMode.VECTOR_ONLY;
    }

    @Test
    void keywordUsesRealMatchingAndNeverEmbedsOrLeaksPlaceholderDistance() throws Exception {
        var result = evaluate(RetrievalMode.KEYWORD_ONLY, 3);
        assertEquals(List.of("1", "2"), ids(result));
        assertEquals(.8, result.results().get(0).keywordScore(), 1e-12);
        assertEquals(.15, result.results().get(1).keywordScore(), 1e-12);
        for (var entry : result.results()) {
            assertNull(entry.vectorRank());
            assertNull(entry.distance());
            assertNull(entry.rrfScore());
            assertNull(entry.rrfNormalized());
            assertNull(entry.timeScore());
            assertEquals(entry.keywordScore(), entry.finalScore());
        }
        verifyNoInteractions(embedding);
        assertTrue(sqlCalls.get(0).contains("ORDER BY importance DESC LIMIT 100"));
        assertTrue(sqlCalls.get(0).contains("COALESCE(last_accessed, created_at)"));
        assertTrue(sqlCalls.get(0).contains("CASE WHEN layer = 2 THEN 5.0 ELSE 1.0 END"));
        verify(statements.get(0)).setString(1, "eval_test_user");
        verify(statements.get(0)).setTimestamp(2, Timestamp.valueOf(EVALUATION_AS_OF));
        verify(statements.get(0)).setDouble(3, .1);
        assertSelectOnly(1);
    }

    @Test
    void vectorPreservesDatabaseDistanceOrderAndDoesNotInventScores() throws Exception {
        var result = evaluate(RetrievalMode.VECTOR_ONLY, 3);
        assertEquals(List.of("3", "2", "1"), ids(result));
        for (int i = 0; i < 3; i++) {
            var entry = result.results().get(i);
            assertEquals(i + 1, entry.vectorRank());
            assertEquals(semantic.get(i).distance(), entry.distance());
            assertNull(entry.keywordRank());
            assertNull(entry.keywordScore());
            assertNull(entry.rrfScore());
            assertNull(entry.rrfNormalized());
            assertNull(entry.finalScore());
            assertNull(entry.importanceContribution());
        }
        verify(embedding).embed("羽毛球");
        verify(statements.get(0)).setString(2, "eval_test_user");
        verify(statements.get(0)).setTimestamp(3, Timestamp.valueOf(EVALUATION_AS_OF));
        verify(statements.get(0)).setDouble(4, .1);
        verify(statements.get(0)).setInt(6, 20);
        assertTrue(sqlCalls.get(0).contains("ORDER BY embedding <=> ?::vector LIMIT ?"));
        assertSelectOnly(1);
    }

    @Test
    void rrfUsesOneBasedRanksSixtyAndRealDeduplication() {
        var result = evaluate(RetrievalMode.RRF, 10);
        assertEquals(List.of("1", "2", "3"), ids(result));
        var first = result.results().get(0);
        assertEquals(3, first.vectorRank());
        assertEquals(1, first.keywordRank());
        assertEquals(1.0 / 63 + 1.0 / 61, first.rrfScore(), 1e-12);
        assertEquals(first.rrfScore(), first.finalScore());
        assertNull(first.rrfNormalized());
        assertNull(first.timeScore());
        assertNull(first.highImportanceBonus());
        assertNull(result.results().get(2).keywordScore());
        assertEquals(1.0 / 61, result.results().get(2).rrfScore(), 1e-12);
        assertSelectOnly(2);
    }

    @Test
    void fullUsesSharedExactScoreAndActualContributions() {
        var result = evaluate(RetrievalMode.FULL, 10);
        assertEquals(List.of("2", "1", "3"), ids(result));
        for (var entry : result.results()) {
            assertNull(entry.rrfNormalized());
            assertEquals(entry.importance() * .2, entry.importanceContribution(), 1e-12);
            assertEquals(entry.confidence() * .05, entry.confidenceContribution(), 1e-12);
            assertEquals(entry.importance() >= .6 ? .05 : 0, entry.highImportanceBonus(), 1e-12);
            assertEquals(entry.rrfScore() * .5 + entry.timeScore() * .2
                + entry.importanceContribution() + entry.confidenceContribution()
                + entry.highImportanceBonus(), entry.finalScore(), 1e-12);
        }
        assertSelectOnly(2);
    }

    @Test
    void normalizedModeUsesDocumentedRrfMaximumAndDualLaneRankOne() {
        assertEquals(2.0 / 61.0, PgVectorMemoryService.RRF_MAX, 0.0);
        var candidate = memory("10", "羽毛球", Timestamp.valueOf(EVALUATION_AS_OF.minusHours(1)),
            .1, .5, .8, "neutral", 3);
        semantic = List.of(candidate);
        keyword = List.of(candidate);

        var entry = evaluate(RetrievalMode.FULL_RRF_NORM, 1).results().get(0);
        assertEquals(1, entry.vectorRank());
        assertEquals(1, entry.keywordRank());
        assertEquals(2.0 / 61.0, entry.rrfScore(), 1e-12);
        assertEquals(1.0, entry.rrfNormalized(), 1e-12);
        assertSelectOnly(2);
    }

    @Test
    void normalizedModeMapsSingleLaneRankOneToHalf() {
        var candidate = memory("10", "羽毛球", Timestamp.valueOf(EVALUATION_AS_OF.minusHours(1)),
            .1, .5, .8, "neutral", 3);
        semantic = List.of(candidate);
        keyword = List.of();

        var entry = evaluate(RetrievalMode.FULL_RRF_NORM, 1).results().get(0);
        assertEquals(1, entry.vectorRank());
        assertNull(entry.keywordRank());
        assertEquals(1.0 / 61.0, entry.rrfScore(), 1e-12);
        assertEquals(.5, entry.rrfNormalized(), 1e-12);
        assertSelectOnly(2);
    }

    @Test
    void normalizedRrfIsClampedToUnitInterval() {
        assertEquals(0.0, PgVectorMemoryService.normalizeRrfScore(-1), 0.0);
        assertEquals(0.0, PgVectorMemoryService.normalizeRrfScore(0), 0.0);
        assertEquals(.5, PgVectorMemoryService.normalizeRrfScore(1.0 / 61.0), 1e-12);
        assertEquals(1.0, PgVectorMemoryService.normalizeRrfScore(PgVectorMemoryService.RRF_MAX), 0.0);
        assertEquals(1.0, PgVectorMemoryService.normalizeRrfScore(1), 0.0);
    }

    @Test
    void normalizedModeFinalScoreMatchesIndependentHandCalculation() {
        Timestamp at = Timestamp.valueOf(EVALUATION_AS_OF.minusHours(72));
        var candidate = memory("10", "羽毛球", at, .1, .7, .8, "positive", 2);
        semantic = List.of(candidate);
        keyword = List.of(candidate);

        var entry = evaluate(RetrievalMode.FULL_RRF_NORM, 1).results().get(0);
        double expected = .5 + Math.exp(-72.0 / 121) * .2
            + .7 * .2 + .8 * .05 + .05;
        assertEquals(Math.exp(-72.0 / 121), entry.timeScore(), 1e-12);
        assertEquals(expected, entry.finalScore(), 1e-12);
        assertEquals(entry.timeScore() * .2 + entry.importanceContribution()
            + entry.confidenceContribution() + entry.highImportanceBonus() + .5,
            entry.finalScore(), 1e-12);
        assertSelectOnly(2);
    }

    @Test
    void sameDataAndFixedAsOfAreIdenticalAcrossWallClockProgress() throws Exception {
        var first = evaluate(RetrievalMode.FULL_RRF_NORM, 3);
        Thread.sleep(20); // Old System.currentTimeMillis scoring would drift here.
        var second = evaluate(RetrievalMode.FULL_RRF_NORM, 3);

        assertEquals(first, second);
        assertSelectOnly(4);
    }

    @Test
    void retentionBoundaryAndCandidateSetDependOnlyOnExplicitAsOf() throws Exception {
        reset(jdbc);
        sqlCalls.clear();
        statements.clear();
        var boundary = memory("10", "羽毛球", Timestamp.valueOf(EVALUATION_AS_OF.minusHours(2)),
            .1, .11, 1, "neutral", 3);
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            PreparedStatementSetter setter = invocation.getArgument(1);
            PreparedStatement ps = mock(PreparedStatement.class);
            Timestamp[] boundAsOf = new Timestamp[1];
            doAnswer(call -> { boundAsOf[0] = call.getArgument(1); return null; })
                .when(ps).setTimestamp(eq(3), any(Timestamp.class));
            setter.setValues(ps);
            sqlCalls.add(sql);
            statements.add(ps);
            double hours = Duration.between(
                boundary.createdAt().toLocalDateTime(), boundAsOf[0].toLocalDateTime()).toMillis()
                / 3600000.0;
            double retention = boundary.importance() * Math.exp(-hours / 25.0);
            return retention > .1 ? List.of(boundary) : List.of();
        }).when(jdbc).query(anyString(), any(PreparedStatementSetter.class),
            org.mockito.ArgumentMatchers.<RowMapper<PgVectorMemoryService.MemoryResult>>any());

        var early = evaluate(RetrievalMode.VECTOR_ONLY, 1, EVALUATION_AS_OF);
        var late = evaluate(RetrievalMode.VECTOR_ONLY, 1, EVALUATION_AS_OF.plusHours(2));

        assertEquals(List.of("10"), ids(early));
        assertTrue(late.results().isEmpty());
        verify(statements.get(0)).setTimestamp(3, Timestamp.valueOf(EVALUATION_AS_OF));
        verify(statements.get(1)).setTimestamp(3, Timestamp.valueOf(EVALUATION_AS_OF.plusHours(2)));
        assertSelectOnly(2);
    }

    @Test
    void h2RrfNormOnlyUsesNormalizedRetrievalWithoutMetadata() {
        var candidate = memory("10", "羽毛球", Timestamp.valueOf(EVALUATION_AS_OF.minusHours(72)),
            .1, .7, .8, "positive", 2);
        semantic = List.of(candidate);
        keyword = List.of(candidate);

        var entry = evaluate(RetrievalMode.RRF_NORM_ONLY, 1).results().get(0);
        assertEquals(2.0 / 61.0, entry.rrfScore(), 1e-12);
        assertEquals(1.0, entry.rrfNormalized(), 1e-12);
        assertEquals(1.0, entry.finalScore(), 1e-12);
        assertEquals(.14, entry.importanceContribution(), 1e-12);
        assertEquals(.04, entry.confidenceContribution(), 1e-12);
        assertEquals(.05, entry.highImportanceBonus(), 1e-12);
        assertSelectOnly(2);
    }

    @Test
    void h2RrfNormTimeAddsOnlyTimeContribution() {
        Timestamp at = Timestamp.valueOf(EVALUATION_AS_OF.minusHours(72));
        var candidate = memory("10", "羽毛球", at, .1, .7, .8, "positive", 2);
        semantic = List.of(candidate);
        keyword = List.of(candidate);

        var entry = evaluate(RetrievalMode.RRF_NORM_TIME, 1).results().get(0);
        assertEquals(.5 + Math.exp(-72.0 / 121) * .2, entry.finalScore(), 1e-12);
        assertEquals(.5 + entry.timeScore() * .2, entry.finalScore(), 1e-12);
        assertSelectOnly(2);
    }

    @Test
    void h2RrfNormImportanceAddsImportanceWithoutBonus() {
        var candidate = memory("10", "羽毛球", Timestamp.valueOf(EVALUATION_AS_OF.minusHours(72)),
            .1, .7, .8, "positive", 2);
        semantic = List.of(candidate);
        keyword = List.of(candidate);

        var entry = evaluate(RetrievalMode.RRF_NORM_IMPORTANCE, 1).results().get(0);
        assertEquals(.5 + .7 * .2, entry.finalScore(), 1e-12);
        assertEquals(.14, entry.importanceContribution(), 1e-12);
        assertEquals(.05, entry.highImportanceBonus(), 1e-12);
        assertSelectOnly(2);
    }

    @Test
    void h2RrfNormImportanceBonusAddsThresholdBonus() {
        var candidate = memory("10", "羽毛球", Timestamp.valueOf(EVALUATION_AS_OF.minusHours(72)),
            .1, .7, .8, "positive", 2);
        semantic = List.of(candidate);
        keyword = List.of(candidate);

        var entry = evaluate(RetrievalMode.RRF_NORM_IMPORTANCE_BONUS, 1).results().get(0);
        assertEquals(.5 + .7 * .2 + .05, entry.finalScore(), 1e-12);
        assertEquals(.14, entry.importanceContribution(), 1e-12);
        assertEquals(.05, entry.highImportanceBonus(), 1e-12);
        assertSelectOnly(2);
    }

    @ParameterizedTest
    @EnumSource(RetrievalMode.class)
    void everyModeAppliesFinalTopKWithoutWriting(RetrievalMode mode) {
        assertEquals(1, evaluate(mode, 1).results().size());
        assertSelectOnly(usesBothLanes(mode) ? 2 : 1);
    }

    @ParameterizedTest
    @EnumSource(RetrievalMode.class)
    void validEmptyIsOkRatherThanFailure(RetrievalMode mode) {
        semantic = List.of();
        keyword = List.of();
        var result = evaluate(mode, 10);
        assertEquals("OK", result.status());
        assertTrue(result.results().isEmpty());
        assertSelectOnly(usesBothLanes(mode) ? 2 : 1);
    }

    @ParameterizedTest
    @EnumSource(value = RetrievalMode.class, names = {"VECTOR_ONLY", "RRF", "FULL", "FULL_RRF_NORM",
        "RRF_NORM_ONLY", "RRF_NORM_TIME", "RRF_NORM_IMPORTANCE", "RRF_NORM_IMPORTANCE_BONUS"})
    void nullEmbeddingIsFailureBeforeSql(RetrievalMode mode) {
        when(embedding.embed(anyString())).thenReturn(null);
        var error = assertThrows(PgVectorMemoryService.EvaluationFailure.class, () -> evaluate(mode, 3));
        assertEquals("EMBEDDING_FAILED", error.code());
        verifyNoInteractions(jdbc);
    }

    @Test
    void thrownEmbeddingFailureIsExplicit() {
        when(embedding.embed(anyString())).thenThrow(new IllegalStateException("upstream unavailable"));
        assertEquals("EMBEDDING_FAILED", assertThrows(PgVectorMemoryService.EvaluationFailure.class,
            () -> evaluate(RetrievalMode.FULL, 3)).code());
        verifyNoInteractions(jdbc);
    }

    @Test
    void invalidEmbeddingDimensionsNonFiniteAndZeroAreExplicitFailures() {
        float[] nan = vector.clone();
        nan[1] = Float.NaN;
        float[] infinite = vector.clone();
        infinite[1] = Float.POSITIVE_INFINITY;
        for (float[] invalid : List.of(new float[3], new float[1024], nan, infinite)) {
            when(embedding.embed(anyString())).thenReturn(invalid);
            assertEquals("INVALID_EMBEDDING", assertThrows(PgVectorMemoryService.EvaluationFailure.class,
                () -> evaluate(RetrievalMode.VECTOR_ONLY, 3)).code());
        }
        verifyNoInteractions(jdbc);
    }

    @ParameterizedTest
    @EnumSource(RetrievalMode.class)
    void sqlFailureNeverMasqueradesAsEmpty(RetrievalMode mode) {
        doThrow(new DataAccessResourceFailureException("SQL failed"))
            .when(jdbc).query(anyString(), any(PreparedStatementSetter.class),
                org.mockito.ArgumentMatchers.<RowMapper<PgVectorMemoryService.MemoryResult>>any());
        assertEquals("SQL_FAILED", assertThrows(PgVectorMemoryService.EvaluationFailure.class,
            () -> evaluate(mode, 3)).code());
    }

    @Test
    void secondLaneFailureAlsoAbortsEvaluation() {
        doThrow(new DataAccessResourceFailureException("keyword failed"))
            .when(jdbc).query(contains("ORDER BY importance"), any(PreparedStatementSetter.class),
                org.mockito.ArgumentMatchers.<RowMapper<PgVectorMemoryService.MemoryResult>>any());
        assertEquals("SQL_FAILED", assertThrows(PgVectorMemoryService.EvaluationFailure.class,
            () -> evaluate(RetrievalMode.RRF, 3)).code());
    }

    @Test
    void serviceRejectsInvalidInputsWithoutIoAndDeclaresReadOnlyTransaction() throws Exception {
        assertThrows(SecurityException.class, () -> service.searchForEvaluation(
            "real_user", "x", RetrievalMode.RRF, 3, EVALUATION_AS_OF));
        assertThrows(IllegalArgumentException.class, () -> service.searchForEvaluation(
            "eval_test_user", " ", RetrievalMode.RRF, 3, EVALUATION_AS_OF));
        assertThrows(IllegalArgumentException.class, () -> service.searchForEvaluation(
            "eval_test_user", null, RetrievalMode.RRF, 3, EVALUATION_AS_OF));
        assertThrows(IllegalArgumentException.class, () -> service.searchForEvaluation(
            "eval_test_user", "x", null, 3, EVALUATION_AS_OF));
        assertThrows(IllegalArgumentException.class, () -> service.searchForEvaluation(
            "eval_test_user", "x", RetrievalMode.RRF, 2, EVALUATION_AS_OF));
        assertThrows(IllegalArgumentException.class, () -> service.searchForEvaluation(
            "eval_test_user", "x", RetrievalMode.RRF, 3, null));
        verifyNoInteractions(jdbc, embedding);
        assertTrue(PgVectorMemoryService.class.getMethod("searchForEvaluation", String.class, String.class,
            RetrievalMode.class, int.class, LocalDateTime.class)
            .getAnnotation(Transactional.class).readOnly());
    }

    @Test
    void productionStillUsesFullRankingAndTouchesOnlyReturnedMemories() {
        var result = service.search("production_user", "羽毛球", vector, 1);
        assertEquals(List.of("2"), result.stream().map(PgVectorMemoryService.MemoryResult::id).toList());
        verify(jdbc).update(contains("UPDATE long_term_memory SET access_count=COALESCE(access_count,0)+1"),
            eq("production_user"), eq("2"));
        verify(jdbc, times(2)).query(anyString(), any(PreparedStatementSetter.class),
            org.mockito.ArgumentMatchers.<RowMapper<PgVectorMemoryService.MemoryResult>>any());
        verifyNoMoreInteractions(jdbc);
        verifyNoInteractions(embedding);
    }

    @Test
    void productionNullVectorStillReturnsEmptyWithoutKeywordFallback() {
        assertTrue(service.search("user", "羽毛球", (float[]) null, 3).isEmpty());
        verifyNoInteractions(jdbc, embedding);
        when(embedding.embed(anyString())).thenReturn(null);
        assertTrue(service.search("user", "羽毛球", 3).isEmpty());
        verifyNoInteractions(jdbc);
    }

    @Test
    void productionKeepsZeroAndNegativeTopKBehavior() {
        assertTrue(service.search("user", "羽毛球", vector, 0).isEmpty());
        assertTrue(service.search("user", "羽毛球", vector, -1).isEmpty());
        verify(jdbc, times(4)).query(anyString(), any(PreparedStatementSetter.class),
            org.mockito.ArgumentMatchers.<RowMapper<PgVectorMemoryService.MemoryResult>>any());
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void productionStillDegradesOneFailedLaneAndSwallowsBothFailedLanes() {
        doThrow(new DataAccessResourceFailureException("semantic failed"))
            .when(jdbc).query(contains("embedding <=>"), any(PreparedStatementSetter.class),
                org.mockito.ArgumentMatchers.<RowMapper<PgVectorMemoryService.MemoryResult>>any());
        assertFalse(service.search("user", "羽毛球", vector, 3).isEmpty());
        doThrow(new DataAccessResourceFailureException("keyword failed"))
            .when(jdbc).query(contains("ORDER BY importance"), any(PreparedStatementSetter.class),
                org.mockito.ArgumentMatchers.<RowMapper<PgVectorMemoryService.MemoryResult>>any());
        assertTrue(service.search("user", "羽毛球", vector, 3).isEmpty());
    }

    @Test
    void productionKeywordFailureStillReturnsSemanticAndTouchFailureIsSwallowed() {
        doThrow(new DataAccessResourceFailureException("keyword failed"))
            .when(jdbc).query(contains("ORDER BY importance"), any(PreparedStatementSetter.class),
                org.mockito.ArgumentMatchers.<RowMapper<PgVectorMemoryService.MemoryResult>>any());
        when(jdbc.update(anyString(), eq("user"), anyString()))
            .thenThrow(new DataAccessResourceFailureException("touch failed"));
        assertEquals(3, service.search("user", "羽毛球", vector, 3).size());
    }

    @Test
    void extractedScoreMatchesOriginalFormulaAndIgnoresDirectEmotionAndLayer() {
        Timestamp at = new Timestamp(System.currentTimeMillis() - 72 * 3600000L);
        var memory = memory("1", "text", at, .1, .7, .8, "positive", 2);
        long before = System.currentTimeMillis();
        double actual = ReflectionTestUtils.invokeMethod(service, "rerankScore", memory, .03);
        long after = System.currentTimeMillis();
        // Independent oracle in tests only, not another production algorithm.
        double upper = .03 * .5 + Math.exp(-((before - at.getTime()) / 3600000.0) / 121) * .2 + .7 * .2 + .8 * .05 + .05;
        double lower = .03 * .5 + Math.exp(-((after - at.getTime()) / 3600000.0) / 121) * .2 + .7 * .2 + .8 * .05 + .05;
        assertTrue(actual >= lower - 1e-12 && actual <= upper + 1e-12);
        var differentTags = memory("1", "text", at, .1, .7, .8, "negative", 3);
        double tagged = ReflectionTestUtils.invokeMethod(service, "rerankScore", differentTags, .03);
        assertEquals(actual, tagged, 1e-6);
    }

    @Test
    void semanticRecordWinsDuplicateIdAndNullIdStillFallsBackToContent() {
        var original = semantic.get(2);
        keyword = List.of(memory(original.id(), original.content(), original.createdAt(), .5, .99, .2, "other", 2));
        var result = evaluate(RetrievalMode.RRF, 10);
        var duplicate = result.results().stream().filter(e -> "1".equals(e.memoryId())).findFirst().orElseThrow();
        assertEquals(.4, duplicate.importance());
        assertEquals(.3, duplicate.distance());
        var legacy = memory(null, "羽毛球", original.createdAt(), .2, .5, 1, "neutral", 3);
        semantic = List.of(legacy);
        keyword = List.of(legacy);
        result = evaluate(RetrievalMode.RRF, 10);
        assertEquals(1, result.results().size());
        assertNull(result.results().get(0).memoryId());
        assertEquals(2.0 / 61, result.results().get(0).rrfScore(), 1e-12);
    }
}
