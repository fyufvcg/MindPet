package controller;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import model.RetrievalDebugResult;
import model.RetrievalMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import service.PgVectorMemoryService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Set;

/** Opt-in local API; never routes evaluation through the mutating production search. */
@RestController
@RequestMapping("/api/eval/memory")
@ConditionalOnProperty(
    prefix = "app.eval.retrieval", name = "enabled",
    havingValue = "true", matchIfMissing = false
)
public class EvalMemoryController {
    private static final Set<Integer> TOP_K = Set.of(1, 3, 5, 10);
    private static final Set<String> LOOPBACK = Set.of(
        "127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "::ffff:127.0.0.1"
    );

    private final PgVectorMemoryService memoryService;
    private final String configuredToken;

    public EvalMemoryController(
        PgVectorMemoryService memoryService,
        @Value("${app.eval.retrieval.token:${APP_EVAL_RETRIEVAL_TOKEN:}}") String configuredToken
    ) {
        this.memoryService = memoryService;
        this.configuredToken = configuredToken;
    }

    @PostMapping("/search")
    public ResponseEntity<?> search(
        @RequestHeader(name = "X-MindPet-Eval-Token", required = false) String token,
        @RequestBody(required = false) JsonNode body,
        HttpServletRequest request
    ) {
        if (configuredToken == null || configuredToken.isBlank()) {
            return failed(HttpStatus.SERVICE_UNAVAILABLE, "EVAL_TOKEN_NOT_CONFIGURED",
                "Configure the evaluation token before using this API");
        }
        if (token == null || !MessageDigest.isEqual(
            configuredToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            return failed(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Invalid evaluation token");
        }
        // Do not trust X-Forwarded-For to grant local access.
        if (!LOOPBACK.contains(request.getRemoteAddr())) {
            return failed(HttpStatus.FORBIDDEN, "LOCAL_ACCESS_ONLY", "Only local requests are allowed");
        }
        if (body == null || !body.isObject()) return invalidRequest();
        String query = text(body, "query");
        String wireMode = text(body, "mode");
        String userId = text(body, "userId");
        String asOfText = text(body, "asOf");
        JsonNode topKNode = body.get("topK");
        if (query == null || query.isBlank() || wireMode == null || userId == null
            || asOfText == null || asOfText.isBlank()
            || topKNode == null || !topKNode.isIntegralNumber() || !topKNode.canConvertToInt()
            || !TOP_K.contains(topKNode.intValue())) {
            return invalidRequest();
        }
        if (!"eval_test_user".equals(userId)) {
            return failed(HttpStatus.FORBIDDEN, "USER_NOT_ALLOWED", "Only eval_test_user is allowed");
        }
        try {
            RetrievalMode mode = RetrievalMode.fromWireName(wireMode);
            LocalDateTime evaluationAsOf = LocalDateTime.parse(
                asOfText, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return ResponseEntity.ok(memoryService.searchForEvaluation(
                userId, query, mode, topKNode.intValue(), evaluationAsOf));
        } catch (DateTimeParseException | IllegalArgumentException e) {
            return invalidRequest();
        } catch (SecurityException e) {
            return failed(HttpStatus.FORBIDDEN, "USER_NOT_ALLOWED", "Only eval_test_user is allowed");
        } catch (PgVectorMemoryService.EvaluationFailure e) {
            HttpStatus status = switch (e.code()) {
                case "EMBEDDING_FAILED", "INVALID_EMBEDDING" -> HttpStatus.BAD_GATEWAY;
                case "SQL_FAILED" -> HttpStatus.SERVICE_UNAVAILABLE;
                default -> HttpStatus.INTERNAL_SERVER_ERROR;
            };
            return failed(status, e.code(), e.getMessage());
        } catch (RuntimeException e) {
            return failed(HttpStatus.INTERNAL_SERVER_ERROR, "RETRIEVAL_FAILED", "Evaluation retrieval failed");
        }
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<RetrievalDebugResult.Failure> malformedJson() {
        return invalidRequest();
    }

    private static String text(JsonNode body, String field) {
        JsonNode value = body.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static ResponseEntity<RetrievalDebugResult.Failure> invalidRequest() {
        return failed(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
            "Required: nonblank query, supported mode, integer topK in [1,3,5,10], "
                + "userId, and ISO local date-time asOf");
    }

    private static ResponseEntity<RetrievalDebugResult.Failure> failed(
        HttpStatus status, String code, String message
    ) {
        return ResponseEntity.status(status).body(new RetrievalDebugResult.Failure("FAILED", code, message));
    }
}
