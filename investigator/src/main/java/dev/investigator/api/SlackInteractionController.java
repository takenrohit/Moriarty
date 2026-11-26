package dev.investigator.api;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.investigator.report.Approval;
import dev.investigator.report.ApprovalService;
import dev.investigator.report.SlackBlockKitRenderer;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Slack_Interaction_Endpoint: {@code POST /api/v1/slack/interactions}, the Request URL of the Slack app's
 * interactivity (Req 7.2 to 7.5, CP-74).
 *
 * <p>The raw body is read from the servlet input stream, never through {@code @RequestBody} or request
 * parameters, because Spring rebuilds form bodies from parsed parameters and those bytes can differ from
 * what Slack signed. It is verified by {@link SlackSignatureVerifier} before anything is parsed; a bad
 * signature or stale timestamp gives 401 and records nothing. A verified {@code approval_approve} or
 * {@code approval_reject} click records the decision once through {@link ApprovalService}; a repeated
 * click changes nothing. Other actions (the "View report" link) are acknowledged with an empty 200.
 * No action is ever executed.
 *
 * <p>404 when no signing secret is set or Slack is not configured. Bodies over
 * {@value #MAX_BODY_BYTES} bytes get 413. The path is outside {@code /api/v1/alerts}, so
 * {@link WebhookAuthenticationFilter} and {@link RequestSizeLimitFilter} do not apply.
 */
@RestController
public class SlackInteractionController {

    static final String PATH = "/api/v1/slack/interactions";
    /** Slack's block_actions payload repeats the whole message, so this is larger than the alert limit. */
    static final int MAX_BODY_BYTES = 1024 * 1024;

    private static final Logger log = LoggerFactory.getLogger(SlackInteractionController.class);

    private final SlackSignatureVerifier verifier;
    private final ObjectProvider<ApprovalService> approvals;
    private final ObjectMapper objectMapper;

    public SlackInteractionController(SlackSignatureVerifier verifier, ObjectProvider<ApprovalService> approvals,
            ObjectMapper objectMapper) {
        this.verifier = verifier;
        this.approvals = approvals;
        this.objectMapper = objectMapper;
    }

    @PostMapping(PATH)
    public ResponseEntity<?> interact(HttpServletRequest request) throws IOException {
        ApprovalService service = approvals.getIfAvailable();
        if (service == null || !verifier.enabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiErrorResponse.of("not found"));
        }
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            return tooLarge();
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            return tooLarge();
        }
        if (!verifier.verify(request.getHeader(SlackSignatureVerifier.TIMESTAMP_HEADER), body,
                request.getHeader(SlackSignatureVerifier.SIGNATURE_HEADER))) {
            log.warn("rejected Slack interaction with an invalid signature or timestamp");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiErrorResponse.of("invalid Slack signature"));
        }
        JsonNode payload = payload(body);
        if (payload == null) {
            return badRequest("payload must be a form field holding a JSON object");
        }
        JsonNode action = payload.path("actions").path(0);
        String actionId = action.path("action_id").asText("");
        Approval.Status decision = switch (actionId) {
            case SlackBlockKitRenderer.APPROVE_ACTION_ID -> Approval.Status.APPROVED;
            case SlackBlockKitRenderer.REJECT_ACTION_ID -> Approval.Status.REJECTED;
            default -> null;
        };
        if (decision == null) {
            return ResponseEntity.ok().build();
        }
        String approvalId = action.path("value").asText("");
        String userId = clean(payload.path("user").path("id").asText(""), 32);
        if (!ApprovalService.ID_PATTERN.matcher(approvalId).matches() || userId.isEmpty()) {
            return badRequest("approval ID or Slack user is missing");
        }
        String username = clean(payload.path("user").path("username").asText(""), 80);
        String decidedBy = "slack:" + userId + (username.isEmpty() ? "" : " (" + username + ")");

        ApprovalService.Decision result = service.decide(approvalId, decision, decidedBy);
        String text = switch (result.outcome()) {
            case RECORDED -> "Recorded " + decision + " for " + approvalId + " by " + decidedBy
                    + ". The investigator does not run the action.";
            case ALREADY_DECIDED -> "Approval " + approvalId + " was already " + result.approval().status()
                    + " by " + result.approval().decidedBy() + "; nothing changed.";
            case NOT_FOUND -> "Unknown approval " + approvalId + "; nothing recorded.";
        };
        return ResponseEntity.ok(Map.of("outcome", result.outcome().name(), "text", text));
    }

    /** The JSON object in the {@code payload} form field, or {@code null}. */
    private JsonNode payload(byte[] body) {
        for (String pair : new String(body, StandardCharsets.UTF_8).split("&")) {
            if (!pair.startsWith("payload=")) {
                continue;
            }
            try {
                JsonNode node = objectMapper.readTree(
                        URLDecoder.decode(pair.substring("payload=".length()), StandardCharsets.UTF_8));
                return node != null && node.isObject() ? node : null;
            } catch (IllegalArgumentException | JacksonException e) {
                return null;
            }
        }
        return null;
    }

    /** Keeps only characters Slack uses in user IDs and names, cut to {@code max}. */
    private static String clean(String value, int max) {
        String kept = value.replaceAll("[^A-Za-z0-9._-]", "");
        return kept.length() > max ? kept.substring(0, max) : kept;
    }

    private static ResponseEntity<ApiErrorResponse> badRequest(String message) {
        return ResponseEntity.badRequest().body(ApiErrorResponse.of(message));
    }

    private static ResponseEntity<ApiErrorResponse> tooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiErrorResponse.of("request body exceeds " + MAX_BODY_BYTES + " bytes"));
    }
}
