package controller;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import model.ImportanceScoreResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import service.KnowledgeGraphService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

/** Opt-in local endpoint for scoring with the production extraction path without persistence. */
@RestController
@RequestMapping("/api/eval/importance")
@ConditionalOnProperty(
    prefix = "app.eval.importance", name = "enabled",
    havingValue = "true", matchIfMissing = false
)
public class EvalImportanceController {
    private static final Set<String> LOOPBACK = Set.of(
        "127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "::ffff:127.0.0.1"
    );

    private final KnowledgeGraphService knowledgeGraphService;
    private final String configuredToken;

    public EvalImportanceController(
        KnowledgeGraphService knowledgeGraphService,
        @Value("${app.eval.importance.token:${APP_EVAL_IMPORTANCE_TOKEN:}}")
        String configuredToken
    ) {
        this.knowledgeGraphService = knowledgeGraphService;
        this.configuredToken = configuredToken;
    }

    @PostMapping("/score")
    public ResponseEntity<?> score(
        @RequestHeader(name = "X-MindPet-Eval-Token", required = false) String token,
        @RequestBody(required = false) JsonNode body,
        HttpServletRequest request
    ) {
        if (configuredToken == null || configuredToken.isBlank()) {
            return failed(HttpStatus.SERVICE_UNAVAILABLE, "EVAL_TOKEN_NOT_CONFIGURED",
                "Configure the evaluation token before using this API");
        }
        if (token == null || !MessageDigest.isEqual(
            configuredToken.getBytes(StandardCharsets.UTF_8),
            token.getBytes(StandardCharsets.UTF_8))) {
            return failed(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Invalid evaluation token");
        }
        if (!LOOPBACK.contains(request.getRemoteAddr())) {
            return failed(HttpStatus.FORBIDDEN, "LOCAL_ACCESS_ONLY", "Only local requests are allowed");
        }
        if (body == null || !body.isObject()) return invalidRequest();
        String userMessage = text(body, "userMessage");
        JsonNode assistantNode = body.get("assistantContext");
        if (userMessage == null || userMessage.isBlank()
            || (assistantNode != null && !assistantNode.isNull() && !assistantNode.isTextual())) {
            return invalidRequest();
        }
        String assistantContext = assistantNode == null || assistantNode.isNull()
            ? "" : assistantNode.textValue();
        try {
            return ResponseEntity.ok(knowledgeGraphService.scoreImportanceForEvaluation(
                userMessage, assistantContext));
        } catch (IllegalArgumentException e) {
            return invalidRequest();
        } catch (KnowledgeGraphService.ImportanceEvaluationFailure e) {
            HttpStatus status = "MODEL_NOT_CONFIGURED".equals(e.code())
                ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY;
            return failed(status, e.code(), e.getMessage());
        } catch (RuntimeException e) {
            return failed(HttpStatus.INTERNAL_SERVER_ERROR, "IMPORTANCE_SCORING_FAILED",
                "Evaluation importance scoring failed");
        }
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ImportanceScoreResult.Failure> malformedJson() {
        return invalidRequest();
    }

    private static String text(JsonNode body, String field) {
        JsonNode value = body.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static ResponseEntity<ImportanceScoreResult.Failure> invalidRequest() {
        return failed(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
            "Required: nonblank textual userMessage; assistantContext must be textual when present");
    }

    private static ResponseEntity<ImportanceScoreResult.Failure> failed(
        HttpStatus status, String code, String message
    ) {
        return ResponseEntity.status(status)
            .body(new ImportanceScoreResult.Failure("FAILED", code, message));
    }
}
