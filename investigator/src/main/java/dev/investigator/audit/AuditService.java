package dev.investigator.audit;

import dev.investigator.domain.Incident;
import dev.investigator.domain.InvestigationReport;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Append-only audit trail (Req 12.5, 12.7, CP-49): every method inserts a new row and never updates one.
 *
 * <p>Implementations never throw: a failed write is logged and the caller carries on, so auditing can
 * never roll back or block an investigation or a stored report. {@link JdbcAuditService} writes
 * PostgreSQL ({@code postgres} profile); {@link NoOpAuditService} is used otherwise.
 */
public interface AuditService {

    /** The alert as received ({@code payload}) and the Incident normalized from it. */
    void recordAlert(String source, Object payload, Incident incident);

    void recordToolCall(ToolCall call);

    void recordLlmExchange(LlmExchange exchange);

    /**
     * A report produced for an Incident that already has a stored report. The stored report is itself an
     * append-only row of {@code reports} (written by the report store), so this keeps the second report as
     * a distinct row instead of overwriting (Req 12.7).
     */
    void recordReport(InvestigationReport report);

    void recordApproval(Approval approval);

    /** A report draft that failed parsing or validation, and what the Investigator does next (Req 4.5). */
    void recordDraftRejection(DraftRejection rejection);

    /**
     * One tool invocation (Req 12.5).
     *
     * @param status      {@code success}, {@code rejected} or {@code error}
     * @param evidenceIds Evidence stored by the call, possibly none
     * @param error       the error result returned to the model, or null on success
     */
    record ToolCall(String incidentId, String toolName, Map<String, Object> args, String status, long durationMs,
            List<String> evidenceIds, String error) {
    }

    /**
     * One LLM request and its response or error (Req 12.5).
     *
     * @param requestJson  JSON of the request messages (never credentials or HTTP headers)
     * @param responseJson JSON of the response message, or of the error
     */
    record LlmExchange(String incidentId, String model, String requestJson, String responseJson, Integer inputTokens,
            Integer outputTokens, long latencyMs) {
    }

    /**
     * One rejected report draft (Req 4.5).
     *
     * @param attempt 1 for the first draft, 2 for the corrected one
     * @param errors  the parsing or validation errors, as reported in the fallback report
     * @param next    {@link #RETRY} or {@link #FALLBACK}
     */
    record DraftRejection(String incidentId, int attempt, List<String> errors, String next) {

        public static final String RETRY = "retry";
        public static final String FALLBACK = "fallback";
    }

    /** One approval decision (Req 12.5); Phase 2 has no approval flow yet, so nothing calls it. */
    record Approval(String incidentId, String action, String status, Instant requestedAt, String decidedBy,
            Instant decidedAt) {
    }
}
