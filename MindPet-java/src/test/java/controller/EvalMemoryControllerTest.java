package controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import model.RetrievalDebugResult;
import model.RetrievalMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import service.PgVectorMemoryService;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** MockMvc and a minimal web context only; never boots the real application. */
class EvalMemoryControllerTest {
    private PgVectorMemoryService service;
    private MockMvc mvc;
    private String token;
    private static final String BODY = "{\"query\":\"我喜欢什么运动？\",\"mode\":\"rrf\",\"topK\":10,\"userId\":\"eval_test_user\"}";

    @BeforeEach
    void setUp() {
        token = UUID.randomUUID().toString();
        service = mock(PgVectorMemoryService.class);
        mvc = MockMvcBuilders.standaloneSetup(new EvalMemoryController(service, token)).build();
    }

    private MockHttpServletRequestBuilder request(String body) {
        return post("/api/eval/memory/search").contentType(MediaType.APPLICATION_JSON)
            .header("X-MindPet-Eval-Token", token).content(body);
    }

    @Test
    void validRequestInvokesOnlyEvaluationAndEmptyResultsAreOk() throws Exception {
        when(service.searchForEvaluation("eval_test_user", "我喜欢什么运动？", RetrievalMode.RRF, 10))
            .thenReturn(new RetrievalDebugResult("OK", "rrf", 10, List.of()));
        mvc.perform(request(BODY)).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("OK")).andExpect(jsonPath("$.results").isEmpty());
        verify(service).searchForEvaluation("eval_test_user", "我喜欢什么运动？", RetrievalMode.RRF, 10);
        verifyNoMoreInteractions(service);
    }

    @Test
    void missingOrIncorrectTokenCannotReachService() throws Exception {
        mvc.perform(post("/api/eval/memory/search").contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value("FAILED"));
        mvc.perform(post("/api/eval/memory/search").contentType(MediaType.APPLICATION_JSON)
                .header("X-MindPet-Eval-Token", UUID.randomUUID().toString()).content(BODY))
            .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void enabledButUnconfiguredTokenFailsClosed() throws Exception {
        MockMvc unconfigured = MockMvcBuilders.standaloneSetup(new EvalMemoryController(service, "")).build();
        unconfigured.perform(request(BODY)).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("EVAL_TOKEN_NOT_CONFIGURED"));
        verifyNoInteractions(service);
    }

    @Test
    void rejectsNonlocalRequestsWithoutTrustingForwardedHeader() throws Exception {
        mvc.perform(request(BODY).header("X-Forwarded-For", "127.0.0.1")
                .with(r -> { r.setRemoteAddr("192.0.2.10"); return r; }))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("LOCAL_ACCESS_ONLY"));
        verifyNoInteractions(service);
    }

    @Test
    void rejectsOtherUser() throws Exception {
        mvc.perform(request(BODY.replace("eval_test_user", "real_user")))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("USER_NOT_ALLOWED"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "2", "20", "\"1\"", "1.5", "true", "2147483648", "null"})
    void rejectsInvalidOrCoercedTopK(String k) throws Exception {
        mvc.perform(request(BODY.replace("\"topK\":10", "\"topK\":" + k)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value("FAILED"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 5, 10})
    void acceptsOnlyDocumentedTopK(int k) throws Exception {
        when(service.searchForEvaluation(anyString(), anyString(), eq(RetrievalMode.RRF), eq(k)))
            .thenReturn(new RetrievalDebugResult("OK", "rrf", k, List.of()));
        mvc.perform(request(BODY.replace("\"topK\":10", "\"topK\":" + k))).andExpect(status().isOk());
        verify(service).searchForEvaluation("eval_test_user", "我喜欢什么运动？", RetrievalMode.RRF, k);
    }

    @ParameterizedTest
    @ValueSource(strings = {"keyword_only", "vector_only", "rrf", "mindpet_full", "mindpet_full_rrf_norm",
        "mindpet_rrf_norm_only", "mindpet_rrf_norm_time", "mindpet_rrf_norm_importance",
        "mindpet_rrf_norm_importance_bonus"})
    void acceptsExactWireModes(String wire) throws Exception {
        RetrievalMode mode = RetrievalMode.fromWireName(wire);
        when(service.searchForEvaluation(anyString(), anyString(), eq(mode), eq(10)))
            .thenReturn(new RetrievalDebugResult("OK", wire, 10, List.of()));
        mvc.perform(request(BODY.replace("\"rrf\"", "\"" + wire + "\""))).andExpect(status().isOk());
        verify(service).searchForEvaluation("eval_test_user", "我喜欢什么运动？", mode, 10);
    }

    @Test
    void rejectsUnsupportedModeBlankNontextQueryMalformedAndMissingBody() throws Exception {
        for (String body : List.of(BODY.replace("\"rrf\"", "\"FULL\""),
            BODY.replace("我喜欢什么运动？", "  "), BODY.replace("\"我喜欢什么运动？\"", "123"),
            "[]", "{broken", "{}", "null")) {
            mvc.perform(request(body)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("FAILED"));
        }
        mvc.perform(request("")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void sqlAndEmbeddingFailuresAreHttpErrorsNotEmptySuccess() throws Exception {
        when(service.searchForEvaluation(anyString(), anyString(), any(), anyInt()))
            .thenThrow(new PgVectorMemoryService.EvaluationFailure("SQL_FAILED", "PostgreSQL/pgvector retrieval failed", null));
        mvc.perform(request(BODY)).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.status").value("FAILED")).andExpect(jsonPath("$.code").value("SQL_FAILED"));
        for (String code : List.of("EMBEDDING_FAILED", "INVALID_EMBEDDING")) {
            doThrow(new PgVectorMemoryService.EvaluationFailure(code, "Query embedding failed", null))
                .when(service).searchForEvaluation(anyString(), anyString(), any(), anyInt());
            mvc.perform(request(BODY)).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value("FAILED")).andExpect(jsonPath("$.code").value(code));
        }
        doThrow(new IllegalStateException("sensitive SQL details"))
            .when(service).searchForEvaluation(anyString(), anyString(), any(), anyInt());
        mvc.perform(request(BODY)).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.message").value("Evaluation retrieval failed"));
    }

    @Test
    void debugJsonPreservesExplicitNullEvenWithGlobalNonNullSetting() throws Exception {
        var entry = new RetrievalDebugResult.Entry("1", "content", 1, null, null, .1, null, null,
            .5, 1, 3, "neutral", null, null, null, null, null, null);
        ObjectMapper mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        var json = mapper.readTree(mapper.writeValueAsString(new RetrievalDebugResult("OK", "vector_only", 1, List.of(entry))))
            .get("results").get(0);
        assertTrue(json.has("rrfScore") && json.get("rrfScore").isNull());
        assertTrue(json.has("rrfNormalized") && json.get("rrfNormalized").isNull());
        assertTrue(json.has("finalScore") && json.get("finalScore").isNull());
        assertTrue(json.has("keywordRank") && json.get("keywordRank").isNull());
        assertFalse(json.has("retentionRate"));
        assertFalse(json.has("emotionContribution"));
        assertFalse(json.has("layerContribution"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import(EvalMemoryController.class)
    static class MvcConfiguration {}

    @Test
    void controllerAndRouteAreAbsentByDefaultAndWhenDisabled() {
        var runner = new WebApplicationContextRunner().withUserConfiguration(MvcConfiguration.class)
            .withBean(PgVectorMemoryService.class, () -> service);
        runner.run(context -> {
            assertEquals(0, context.getBeansOfType(EvalMemoryController.class).size());
            MockMvcBuilders.webAppContextSetup(context).build().perform(request(BODY)).andExpect(status().isNotFound());
        });
        runner.withPropertyValues("app.eval.retrieval.enabled=false").run(context -> {
            assertEquals(0, context.getBeansOfType(EvalMemoryController.class).size());
            MockMvcBuilders.webAppContextSetup(context).build().perform(request(BODY)).andExpect(status().isNotFound());
        });
    }

    @Test
    void controllerIsRegisteredOnlyWhenEnabledAndResolvesEnvironmentToken() {
        when(service.searchForEvaluation(anyString(), anyString(), any(), anyInt()))
            .thenReturn(new RetrievalDebugResult("OK", "rrf", 10, List.of()));
        new WebApplicationContextRunner().withUserConfiguration(MvcConfiguration.class)
            .withBean(PgVectorMemoryService.class, () -> service)
            .withPropertyValues("app.eval.retrieval.enabled=true", "APP_EVAL_RETRIEVAL_TOKEN=" + token)
            .run(context -> {
                assertNotNull(context.getBean(EvalMemoryController.class));
                MockMvcBuilders.webAppContextSetup(context).build().perform(request(BODY)).andExpect(status().isOk());
            });
    }
}
