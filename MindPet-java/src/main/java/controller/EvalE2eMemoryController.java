package controller;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import model.E2eMemoryIngestResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
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

/** Opt-in, local-only E2E memory write API. It is absent unless explicitly enabled. */
@RestController
@RequestMapping("/api/eval/memory")
@ConditionalOnProperty(
    prefix = "app.eval.e2e-memory", name = "enabled",
    havingValue = "true", matchIfMissing = false
)
public class EvalE2eMemoryController {
    private static final Set<String> LOOPBACK = Set.of(
        "127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "::ffff:127.0.0.1");

    private final E2eMemoryEvaluationService service;
    private final String configuredToken;

    public EvalE2eMemoryController(
            E2eMemoryEvaluationService service,
            @Value("${app.eval.e2e-memory.token:${APP_EVAL_E2E_MEMORY_TOKEN:}}")
            String configuredToken) {
        this.service = service;
        this.configuredToken = configuredToken;
    }

    @PostMapping("/ingest")
    public ResponseEntity<?> ingest(
            @RequestHeader(name = "X-MindPet-Eval-Token", required = false) String token,
            @RequestBody(required = false) JsonNode body,
            HttpServletRequest request) {
        ResponseEntity<?> denied = authorize(token, request);
        if (denied != null) return denied;
        if (body == null || !body.isObject()) return invalid(null);

        String sampleId = text(body, "sampleId");
        String runId = optionalText(body, "runId", "pilot");
        String requestedUser = optionalText(body, "userId", E2eMemoryEvaluationService.EVAL_USER);
        String userMessage = text(body, "userMessage");
        String assistantContext = text(body, "assistantContext");
        String emotion = optionalText(body, "emotion", "neutral");
        Instant occurredAt;
        try {
            String value = optionalText(body, "occurredAt", null);
            occurredAt = value == null ? Instant.now() : Instant.parse(value);
        } catch (DateTimeParseException | IllegalArgumentException e) {
            return invalid(sampleId);
        }
        if (sampleId == null || userMessage == null || userMessage.isBlank()
                || assistantContext == null || runId == null || requestedUser == null || emotion == null) {
            return invalid(sampleId);
        }
        try {
            E2eMemoryIngestResult result = service.ingest(
                sampleId, runId, requestedUser, userMessage, assistantContext, emotion, occurredAt);
            HttpStatus status = "FAILED".equals(result.status())
                ? HttpStatus.INTERNAL_SERVER_ERROR : HttpStatus.OK;
            return ResponseEntity.status(status).body(result);
        } catch (E2eMemoryEvaluationService.EvaluationFailure e) {
            return evaluationFailure(sampleId, e);
        } catch (RuntimeException e) {
            return unexpectedFailure(sampleId);
        }
    }

    @GetMapping("/snapshot")
    public ResponseEntity<?> snapshot(
            @RequestHeader(name = "X-MindPet-Eval-Token", required = false) String token,
            HttpServletRequest request) {
        ResponseEntity<?> denied = authorize(token, request);
        if (denied != null) return denied;
        try {
            return ResponseEntity.ok(service.snapshot(E2eMemoryEvaluationService.EVAL_USER));
        } catch (E2eMemoryEvaluationService.EvaluationFailure e) {
            return evaluationFailure(null, e);
        } catch (RuntimeException e) {
            return unexpectedFailure(null);
        }
    }

    @PostMapping("/reset")
    public ResponseEntity<?> reset(
            @RequestHeader(name = "X-MindPet-Eval-Token", required = false) String token,
            @RequestBody(required = false) JsonNode body,
            HttpServletRequest request) {
        ResponseEntity<?> denied = authorize(token, request);
        if (denied != null) return denied;
        String requestedUser = body == null
            ? E2eMemoryEvaluationService.EVAL_USER
            : optionalText(body, "userId", E2eMemoryEvaluationService.EVAL_USER);
        if (body != null && !body.isObject() || requestedUser == null) return invalid(null);
        try {
            return ResponseEntity.ok(service.reset(requestedUser));
        } catch (E2eMemoryEvaluationService.EvaluationFailure e) {
            return evaluationFailure(null, e);
        } catch (RuntimeException e) {
            return unexpectedFailure(null);
        }
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<E2eMemoryIngestResult.Failure> malformedJson() {
        return invalid(null);
    }

    private ResponseEntity<?> authorize(String token, HttpServletRequest request) {
        if (configuredToken == null || configuredToken.isBlank()) {
            return failed(HttpStatus.SERVICE_UNAVAILABLE, null, "AUTHORIZATION",
                "EVAL_TOKEN_NOT_CONFIGURED", "Configure a temporary E2E evaluation token");
        }
        if (token == null || !MessageDigest.isEqual(
                configuredToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            return failed(HttpStatus.UNAUTHORIZED, null, "AUTHORIZATION",
                "UNAUTHORIZED", "Invalid evaluation token");
        }
        if (!LOOPBACK.contains(request.getRemoteAddr())) {
            return failed(HttpStatus.FORBIDDEN, null, "AUTHORIZATION",
                "LOCAL_ACCESS_ONLY", "Only local requests are allowed");
        }
        return null;
    }

    private ResponseEntity<E2eMemoryIngestResult.Failure> evaluationFailure(
            String sampleId, E2eMemoryEvaluationService.EvaluationFailure failure) {
        HttpStatus status = switch (failure.type()) {
            case "USER_NOT_ALLOWED" -> HttpStatus.FORBIDDEN;
            case "WRONG_DATABASE", "DATABASE_CHECK_FAILED" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "COMPLETION_TIMEOUT" -> HttpStatus.GATEWAY_TIMEOUT;
            case "INVALID_INPUT", "INVALID_SAMPLE_ID", "SESSION_ID_TOO_LONG" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.BAD_GATEWAY;
        };
        return failed(status, sampleId, failure.stage(), failure.type(), failure.getMessage());
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
            "Required: sampleId, nonblank userMessage, textual assistantContext; optional IDs must be safe text");
    }

    private static ResponseEntity<E2eMemoryIngestResult.Failure> unexpectedFailure(String sampleId) {
        return failed(HttpStatus.INTERNAL_SERVER_ERROR, sampleId, "EVALUATION_OBSERVATION",
            "EVALUATION_INTERNAL_ERROR", "Evaluation observation failed");
    }

    private static ResponseEntity<E2eMemoryIngestResult.Failure> failed(
            HttpStatus status, String sampleId, String stage, String type, String message) {
        return ResponseEntity.status(status).body(
            new E2eMemoryIngestResult.Failure("FAILED", sampleId, stage, type, message));
    }
}
