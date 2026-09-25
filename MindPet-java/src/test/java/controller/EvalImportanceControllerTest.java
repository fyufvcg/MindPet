package controller;

import model.ImportanceScoreResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import service.KnowledgeGraphService;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EvalImportanceControllerTest {
    private KnowledgeGraphService service;
    private MockMvc mvc;
    private String token;
    private static final String BODY = "{\"userMessage\":\"我长期喜欢羽毛球\","
        + "\"assistantContext\":\"明白了\"}";

    @BeforeEach
    void setUp() {
        service = mock(KnowledgeGraphService.class);
        token = UUID.randomUUID().toString();
        mvc = MockMvcBuilders.standaloneSetup(
            new EvalImportanceController(service, token)).build();
    }

    private MockHttpServletRequestBuilder request(String body) {
        return post("/api/eval/importance/score").contentType(MediaType.APPLICATION_JSON)
            .header("X-MindPet-Eval-Token", token).content(body);
    }

    @Test
    void validRequestReturnsRealServiceFieldsAndOnlyCallsScorer() throws Exception {
        var parse = new ImportanceScoreResult.ParseInfo(true, true, false, false, false, false);
        when(service.scoreImportanceForEvaluation("我长期喜欢羽毛球", "明白了"))
            .thenReturn(new ImportanceScoreResult(
                "OK", "production-model", true, true, 0.8, 0.9, true, true, parse));

        mvc.perform(request(BODY)).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("OK"))
            .andExpect(jsonPath("$.worthRemembering").value(true))
            .andExpect(jsonPath("$.shouldRemember").value(true))
            .andExpect(jsonPath("$.importance").value(0.8))
            .andExpect(jsonPath("$.confidence").value(0.9))
            .andExpect(jsonPath("$.parse.succeeded").value(true));
        verify(service).scoreImportanceForEvaluation("我长期喜欢羽毛球", "明白了");
        verifyNoMoreInteractions(service);
    }

    @Test
    void authenticationAndLoopbackChecksFailClosed() throws Exception {
        mvc.perform(post("/api/eval/importance/score")
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isUnauthorized());
        mvc.perform(request(BODY).header("X-MindPet-Eval-Token", "wrong"))
            .andExpect(status().isUnauthorized());
        mvc.perform(request(BODY).header("X-Forwarded-For", "127.0.0.1")
                .with(req -> { req.setRemoteAddr("192.0.2.10"); return req; }))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("LOCAL_ACCESS_ONLY"));
        verifyNoInteractions(service);
    }

    @Test
    void missingConfiguredTokenFailsClosed() throws Exception {
        MockMvc unconfigured = MockMvcBuilders.standaloneSetup(
            new EvalImportanceController(service, "")).build();
        unconfigured.perform(post("/api/eval/importance/score")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-MindPet-Eval-Token", token).content(BODY))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("EVAL_TOKEN_NOT_CONFIGURED"));
        verifyNoInteractions(service);
    }

    @Test
    void invalidBodiesCannotReachService() throws Exception {
        for (String body : new String[]{"{}", "[]", "null", "{broken",
            "{\"userMessage\":\"  \"}",
            "{\"userMessage\":\"x\",\"assistantContext\":123}"}) {
            mvc.perform(request(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test
    void modelFailuresAreSanitizedHttpErrors() throws Exception {
        when(service.scoreImportanceForEvaluation(anyString(), anyString()))
            .thenThrow(new KnowledgeGraphService.ImportanceEvaluationFailure(
                "MODEL_RESPONSE_INVALID", "Production extraction response could not be parsed", null));
        mvc.perform(request(BODY)).andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.code").value("MODEL_RESPONSE_INVALID"));

        reset(service);
        when(service.scoreImportanceForEvaluation(anyString(), anyString()))
            .thenThrow(new IllegalStateException("secret provider details"));
        mvc.perform(request(BODY)).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.message").value("Evaluation importance scoring failed"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @org.springframework.context.annotation.Import(EvalImportanceController.class)
    static class MvcConfiguration {}

    @Test
    void controllerAndRouteAreAbsentByDefaultAndWhenDisabled() {
        var runner = new WebApplicationContextRunner().withUserConfiguration(MvcConfiguration.class)
            .withBean(KnowledgeGraphService.class, () -> service);
        runner.run(context -> {
            assertEquals(0, context.getBeansOfType(EvalImportanceController.class).size());
            MockMvcBuilders.webAppContextSetup(context).build().perform(request(BODY))
                .andExpect(status().isNotFound());
        });
        runner.withPropertyValues("app.eval.importance.enabled=false").run(context ->
            assertEquals(0, context.getBeansOfType(EvalImportanceController.class).size()));
    }

    @Test
    void controllerRegistersOnlyWhenEnabledAndUsesEnvironmentToken() {
        when(service.scoreImportanceForEvaluation(anyString(), anyString()))
            .thenReturn(new ImportanceScoreResult("OK", "production-model", false, false,
                0.1, 0.9, false, false, new ImportanceScoreResult.ParseInfo(
                    true, true, false, false, false, false)));
        new WebApplicationContextRunner().withUserConfiguration(MvcConfiguration.class)
            .withBean(KnowledgeGraphService.class, () -> service)
            .withPropertyValues(
                "app.eval.importance.enabled=true", "APP_EVAL_IMPORTANCE_TOKEN=" + token)
            .run(context -> {
                assertNotNull(context.getBean(EvalImportanceController.class));
                MockMvcBuilders.webAppContextSetup(context).build().perform(request(BODY))
                    .andExpect(status().isOk());
            });
    }
}
