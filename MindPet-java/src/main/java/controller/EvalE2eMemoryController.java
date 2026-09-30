package controller;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import model.E2eMemoryIngestResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import service.E2eMemoryEvaluationService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;

@RestController
@RequestMapping("/api/eval/memory")
@ConditionalOnProperty(
    prefix = "app.eval.e2e-memory", name = "enabled", havingValue = "true", matchIfMissing = false)
public class EvalE2eMemoryController {
    private static final Set<String> LOOPBACK = Set.of(
        "127.0.0.1", "0:0:0:0:0:0:0:1", "::1");

    private final E2eMemoryEvaluationService service;
    private final String configuredToken;

    public EvalE2eMemoryController(
            E2eMemoryEvaluationService service,
            @Value("${app.eval.e2e-memory.token:${APP_EVAL_E2E_MEMORY_TOKEN:}}")
            String configuredToken) {
        this.service = service;
        this.configuredToken = configuredToken == null ? "" : configuredToken;
    }

    @PostMapping("/ingest")
    public ResponseEntity<?> ingest(
            @RequestHeader(value = "X-MindPet-Eval-Token", required = false) String token,
            HttpServletRequest request,
            @RequestBody JsonNode body) {
        ResponseEntity<?> rejected = authorize(token, request);
        if (rejected != null) return rejected;
        if (body == null || !body.isObject()) return invalid(null);
        String sampleId = text(body, "sampleId");
        String runId = text(body, "runId");
        String userMessage = text(body, "userMessage");
        String assistantContext = text(body, "assistantContext");
        String emotion = optionalText(body, "emotion", "neutral");
        Instant occurredAt;
        try {
            String occurredAtText = optionalText(body, "occurredAt", null);
            occurredAt = occurredAtText == null ? Instant.now() : Instant.parse(occurredAtText);
        } catch (DateTimeParseException exception) {
            return invalid(sampleId);
        }
        if (sampleId == null || runId == null || userMessage == null || userMessage.isBlank()
                || assistantContext == null || emotion == null) {
            return invalid(sampleId);
        }
        try {
            E2eMemoryIngestResult result = service.ingest(
                sampleId, runId, userMessage, assistantContext, emotion, occurredAt);
            HttpStatus status = "FAILED".equals(result.status())
                ? HttpStatus.BAD_GATEWAY : HttpStatus.OK;
            return ResponseEntity.status(status).body(result);
        } catch (E2eMemoryEvaluationService.EvaluationFailure failure) {
            return evaluationFailure(sampleId, failure);
        }
    }

    @PostMapping("/reset")
    public ResponseEntity<?> reset(
            @RequestHeader(value = "X-MindPet-Eval-Token", required = false) String token,
            HttpServletRequest request) {
        ResponseEntity<?> rejected = authorize(token, request);
        if (rejected != null) return rejected;
        try {
            return ResponseEntity.ok(service.reset());
        } catch (E2eMemoryEvaluationService.EvaluationFailure failure) {
            return evaluationFailure(null, failure);
        }
    }

    @GetMapping("/snapshot")
    public ResponseEntity<?> snapshot(
            @RequestHeader(value = "X-MindPet-Eval-Token", required = false) String token,
            HttpServletRequest request) {
        ResponseEntity<?> rejected = authorize(token, request);
        if (rejected != null) return rejected;
        try {
            return ResponseEntity.ok(service.snapshot());
        } catch (E2eMemoryEvaluationService.EvaluationFailure failure) {
            return evaluationFailure(null, failure);
        }
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<E2eMemoryIngestResult.Failure> malformedJson() {
        return failed(HttpStatus.BAD_REQUEST, null, "VALIDATION", "INVALID_JSON", "Request body is invalid JSON");
    }

    private ResponseEntity<?> authorize(String token, HttpServletRequest request) {
        if (configuredToken.isBlank()) {
            return failed(HttpStatus.SERVICE_UNAVAILABLE, null, "AUTHORIZATION",
                "EVAL_TOKEN_NOT_CONFIGURED", "Evaluation token is not configured");
        }
        if (!LOOPBACK.contains(request.getRemoteAddr())) {
            return failed(HttpStatus.FORBIDDEN, null, "AUTHORIZATION",
                "LOCAL_ACCESS_ONLY", "Evaluation API accepts loopback requests only");
        }
        if (token == null || !MessageDigest.isEqual(
                configuredToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            return failed(HttpStatus.UNAUTHORIZED, null, "AUTHORIZATION",
                "INVALID_TOKEN", "Evaluation token is invalid");
        }
        return null;
    }

    private ResponseEntity<E2eMemoryIngestResult.Failure> evaluationFailure(
            String sampleId, E2eMemoryEvaluationService.EvaluationFailure failure) {
        HttpStatus status = switch (failure.type()) {
            case "SQLITE_PATH_NOT_CONFIGURED", "SQLITE_PATH_INVALID", "SQLITE_PATH_MISMATCH",
                 "SQLITE_PATH_OUTSIDE_ALLOWED_ROOT", "PRODUCTION_SQLITE_PATH_FORBIDDEN" ->
                HttpStatus.SERVICE_UNAVAILABLE;
            case "COMPLETION_TIMEOUT" -> HttpStatus.GATEWAY_TIMEOUT;
            case "INVALID_INPUT", "INVALID_SAMPLE_ID", "INVALID_RUN_ID", "SESSION_ID_TOO_LONG" ->
                HttpStatus.BAD_REQUEST;
            default -> HttpStatus.BAD_GATEWAY;
        };
        return ResponseEntity.status(status).body(new E2eMemoryIngestResult.Failure(
            "FAILED", sampleId, failure.stage(), failure.type(), failure.getMessage(), failure.writeTrace()));
    }

    private static String text(JsonNode body, String field) {
        JsonNode value = body.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static String optionalText(JsonNode body, String field, String fallback) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) return fallback;
        return value.isTextual() ? value.textValue() : null;
    }

    private static ResponseEntity<E2eMemoryIngestResult.Failure> invalid(String sampleId) {
        return failed(HttpStatus.BAD_REQUEST, sampleId, "VALIDATION", "INVALID_REQUEST",
            "Required: sampleId, runId, nonblank userMessage, textual assistantContext");
    }

    private static ResponseEntity<E2eMemoryIngestResult.Failure> failed(
            HttpStatus status, String sampleId, String stage, String type, String message) {
        return ResponseEntity.status(status).body(
            new E2eMemoryIngestResult.Failure("FAILED", sampleId, stage, type, message, null));
    }
}
