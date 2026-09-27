package controller;

import model.E2eMemoryIngestResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import service.E2eMemoryEvaluationService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EvalE2eMemoryControllerTest {
    private static final String BODY = """
        {"sampleId":"p001","runId":"pilot01","userId":"e2e_memory_eval_user",
         "userMessage":"我长期喜欢羽毛球","assistantContext":"明白了",
         "occurredAt":"2026-09-26T10:00:00Z"}
        """;

    private E2eMemoryEvaluationService service;
    private MockMvc mvc;
    private String token;

    @BeforeEach
    void setUp() {
        service = mock(E2eMemoryEvaluationService.class);
        token = UUID.randomUUID().toString();
        mvc = MockMvcBuilders.standaloneSetup(new EvalE2eMemoryController(service, token)).build();
    }

    private MockHttpServletRequestBuilder request(String body) {
        return post("/api/eval/memory/ingest")
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-MindPet-Eval-Token", token)
            .content(body);
    }

    private E2eMemoryIngestResult success() {
        return new E2eMemoryIngestResult(
            "FULL_SUCCESS", "p001", "pilot01", E2eMemoryEvaluationService.EVAL_USER,
            "e2e:pilot01:p001", "hash", "model", false, true, true,
            true, true, .7, .9, true, true, 1, 1, 2, true,
            0, 1, false, 0,
            new E2eMemoryIngestResult.RowMapping(List.of("1"), List.of("e1"),
                List.of("r1"), List.of("10", "11")),
            new E2eMemoryIngestResult.ParseInfo(true, false, false, false, false),
            null, null, null);
    }

    @Test
    void validRequestReturnsCompletedPersistenceDiagnostics() throws Exception {
        when(service.ingest(eq("p001"), eq("pilot01"), eq(E2eMemoryEvaluationService.EVAL_USER),
            eq("我长期喜欢羽毛球"), eq("明白了"), eq("neutral"), any())).thenReturn(success());

        mvc.perform(request(BODY)).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FULL_SUCCESS"))
            .andExpect(jsonPath("$.turnCompleted").value(true))
            .andExpect(jsonPath("$.extractionCompleted").value(true))
            .andExpect(jsonPath("$.ltmPersisted").value(true));
    }

    @Test
    void missingWrongTokenAndNonLoopbackNeverReachService() throws Exception {
        mvc.perform(post("/api/eval/memory/ingest").contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isUnauthorized());
        mvc.perform(request(BODY).header("X-MindPet-Eval-Token", UUID.randomUUID()))
            .andExpect(status().isUnauthorized());
        mvc.perform(request(BODY).header("X-Forwarded-For", "127.0.0.1")
                .with(req -> { req.setRemoteAddr("192.0.2.4"); return req; }))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorType").value("LOCAL_ACCESS_ONLY"));
        verifyNoInteractions(service);
    }

    @Test
    void unconfiguredTokenFailsClosed() throws Exception {
        MockMvc unconfigured = MockMvcBuilders
            .standaloneSetup(new EvalE2eMemoryController(service, "")).build();
        unconfigured.perform(request(BODY)).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.errorType").value("EVAL_TOKEN_NOT_CONFIGURED"));
        verifyNoInteractions(service);
    }

    @Test
    void wrongUserAndInvalidSampleAreRejected() throws Exception {
        doThrow(new E2eMemoryEvaluationService.EvaluationFailure(
            "VALIDATION", "USER_NOT_ALLOWED", "fixed user only", null))
            .when(service).ingest(anyString(), anyString(), eq("production-user"),
                anyString(), anyString(), anyString(), any());
        mvc.perform(request(BODY.replace("e2e_memory_eval_user", "production-user")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorType").value("USER_NOT_ALLOWED"));

        doThrow(new E2eMemoryEvaluationService.EvaluationFailure(
            "VALIDATION", "INVALID_SAMPLE_ID", "invalid", null))
            .when(service).ingest(eq("../bad"), anyString(), anyString(),
                anyString(), anyString(), anyString(), any());
        mvc.perform(request(BODY.replace("p001", "../bad")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorType").value("INVALID_SAMPLE_ID"));
    }

    @Test
    void wrongDatabaseIsServiceUnavailable() throws Exception {
        doThrow(new E2eMemoryEvaluationService.EvaluationFailure(
            "DATABASE_GUARD", "WRONG_DATABASE", "wrong database", null))
            .when(service).ingest(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any());
        mvc.perform(request(BODY)).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.errorStage").value("DATABASE_GUARD"))
            .andExpect(jsonPath("$.errorType").value("WRONG_DATABASE"));
    }

    @Test
    void malformedBodyIsRejectedWithoutServiceCall() throws Exception {
        for (String body : List.of("{}", "[]", "{broken", "null",
                BODY.replace("\"p001\"", "123"),
                BODY.replace("\"我长期喜欢羽毛球\"", "null"))) {
            mvc.perform(request(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test
    void resetUsesOnlyFixedEvaluationUser() throws Exception {
        var snapshot = new E2eMemoryIngestResult.Snapshot(
            "OK", E2eMemoryEvaluationService.REQUIRED_DATABASE,
            E2eMemoryEvaluationService.EVAL_USER, Map.of());
        when(service.reset(E2eMemoryEvaluationService.EVAL_USER)).thenReturn(
            new E2eMemoryIngestResult.ResetResult("OK",
                E2eMemoryEvaluationService.REQUIRED_DATABASE,
                E2eMemoryEvaluationService.EVAL_USER, Map.of(), snapshot));

        mvc.perform(post("/api/eval/memory/reset")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-MindPet-Eval-Token", token)
                .content("{\"userId\":\"e2e_memory_eval_user\"}"))
            .andExpect(status().isOk());
        verify(service).reset(E2eMemoryEvaluationService.EVAL_USER);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import(EvalE2eMemoryController.class)
    static class MvcConfiguration {}

    @Test
    void controllerAndRoutesAreAbsentByDefault() {
        new WebApplicationContextRunner().withUserConfiguration(MvcConfiguration.class)
            .withBean(E2eMemoryEvaluationService.class, () -> service)
            .run(context -> {
                assertEquals(0, context.getBeansOfType(EvalE2eMemoryController.class).size());
                MockMvcBuilders.webAppContextSetup(context).build()
                    .perform(request(BODY)).andExpect(status().isNotFound());
            });
    }

    @Test
    void controllerIsRegisteredOnlyWhenExplicitlyEnabled() {
        when(service.ingest(anyString(), anyString(), anyString(),
            anyString(), anyString(), anyString(), any())).thenReturn(success());
        new WebApplicationContextRunner().withUserConfiguration(MvcConfiguration.class)
            .withBean(E2eMemoryEvaluationService.class, () -> service)
            .withPropertyValues(
                "app.eval.e2e-memory.enabled=true",
                "APP_EVAL_E2E_MEMORY_TOKEN=" + token)
            .run(context -> {
                assertNotNull(context.getBean(EvalE2eMemoryController.class));
                MockMvcBuilders.webAppContextSetup(context).build()
                    .perform(request(BODY)).andExpect(status().isOk());
            });
    }
}
