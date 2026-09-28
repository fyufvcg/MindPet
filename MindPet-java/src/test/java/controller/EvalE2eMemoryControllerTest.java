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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EvalE2eMemoryControllerTest {
    private static final String TOKEN = "test-evaluation-token";
    private static final String BODY = """
        {"sampleId":"p001","runId":"pilot01","userId":"desktop-user",
         "userMessage":"hello","assistantContext":"context",
         "occurredAt":"2026-10-01T00:00:00Z"}
        """;

    private E2eMemoryEvaluationService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(E2eMemoryEvaluationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new EvalE2eMemoryController(service, TOKEN)).build();
    }

    @Test
    void wrongTokenAndNonLoopbackAreRejected() throws Exception {
        mvc.perform(post("/api/eval/memory/ingest")
                .contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isUnauthorized());
        mvc.perform(request(BODY).header("X-MindPet-Eval-Token", "wrong"))
            .andExpect(status().isUnauthorized());
        mvc.perform(request(BODY).with(request -> {
                request.setRemoteAddr("192.0.2.20");
                return request;
            }))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorType").value("LOCAL_ACCESS_ONLY"));
        verifyNoInteractions(service);
    }

    @Test
    void clientUserIdCannotOverrideFixedEvaluationUser() throws Exception {
        when(service.ingest(anyString(), anyString(), anyString(), anyString(), anyString(), any()))
            .thenReturn(success());

        mvc.perform(request(BODY)).andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value(E2eMemoryEvaluationService.EVAL_USER));

        verify(service).ingest(
            "p001", "pilot01", "hello", "context", "neutral",
            java.time.Instant.parse("2026-10-01T00:00:00Z"));
    }

    @Test
    void resetAndSnapshotUseGuardedServiceWithoutUserInput() throws Exception {
        E2eMemoryIngestResult.Snapshot snapshot = new E2eMemoryIngestResult.Snapshot(
            "OK", "D:\\eval\\mindpet-e2e.db", E2eMemoryEvaluationService.EVAL_USER,
            "model", "hash", true, Map.of());
        when(service.snapshot()).thenReturn(snapshot);
        when(service.reset()).thenReturn(new E2eMemoryIngestResult.ResetResult(
            "OK", snapshot.databasePath(), snapshot.userId(), Map.of(), snapshot));

        mvc.perform(get("/api/eval/memory/snapshot").header("X-MindPet-Eval-Token", TOKEN))
            .andExpect(status().isOk());
        mvc.perform(post("/api/eval/memory/reset").header("X-MindPet-Eval-Token", TOKEN))
            .andExpect(status().isOk());
        verify(service).snapshot();
        verify(service).reset();
    }

    @Test
    void controllerIsAbsentByDefaultAndPresentOnlyWhenEnabled() {
        new WebApplicationContextRunner()
            .withUserConfiguration(MvcConfiguration.class)
            .withBean(E2eMemoryEvaluationService.class, () -> service)
            .run(context -> assertThat(context).doesNotHaveBean(EvalE2eMemoryController.class));

        new WebApplicationContextRunner()
            .withUserConfiguration(MvcConfiguration.class)
            .withBean(E2eMemoryEvaluationService.class, () -> service)
            .withPropertyValues(
                "app.eval.e2e-memory.enabled=true",
                "app.eval.e2e-memory.token=" + TOKEN)
            .run(context -> assertThat(context).hasSingleBean(EvalE2eMemoryController.class));
    }

    private MockHttpServletRequestBuilder request(String body) {
        return post("/api/eval/memory/ingest")
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-MindPet-Eval-Token", TOKEN)
            .content(body);
    }

    private E2eMemoryIngestResult success() {
        return new E2eMemoryIngestResult(
            "NO_PERSIST", "p001", "pilot01", E2eMemoryEvaluationService.EVAL_USER,
            "e2e:pilot01:p001", "hash", "model", false, true, true,
            false, .2, .9, false, false, 0, 0, 0, true,
            0, 0, false, 0,
            new E2eMemoryIngestResult.RowMapping(List.of(), List.of(), List.of(), List.of()),
            List.of(), List.of(), null, null, null);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import(EvalE2eMemoryController.class)
    static class MvcConfiguration {}
}
