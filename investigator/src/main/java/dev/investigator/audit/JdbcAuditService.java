package dev.investigator.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.investigator.config.JacksonConfig;
import dev.investigator.domain.Incident;
import dev.investigator.domain.InvestigationReport;
import dev.investigator.store.jdbc.JdbcReportRepository;
import dev.investigator.store.jdbc.PgValues;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * PostgreSQL {@link AuditService}: plain INSERTs into {@code alerts_raw}, {@code tool_calls},
 * {@code llm_exchanges}, {@code reports} and {@code approvals} (Req 12.5, 12.7). The application role has
 * no UPDATE or DELETE grant on these tables (V2__audit.sql, Req 12.6). Each insert runs on its own
 * auto-committed connection, so a failure is logged and affects nothing else (CP-49).
 */
@Service
@Profile("postgres")
public class JdbcAuditService implements AuditService {

    /** JSON longer than this many characters is stored as a truncated prefix. */
    static final int MAX_JSON_CHARS = 64 * 1024;

    /** {@code tool_calls.tool_name} of a rejected report draft; not a real tool. */
    static final String DRAFT_REJECTION_TOOL = "report_validation";

    private static final Logger log = LoggerFactory.getLogger(JdbcAuditService.class);

    private final JdbcClient jdbc;
    private final ObjectMapper codec = JacksonConfig.jsonCodec();

    public JdbcAuditService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void recordAlert(String source, Object payload, Incident incident) {
        insert("alerts_raw", () -> jdbc.sql("""
                INSERT INTO alerts_raw (incident_id, source, payload, incident)
                VALUES (:incidentId, :source, CAST(:payload AS jsonb), CAST(:incident AS jsonb))
                """)
                .param("incidentId", incident.id())
                .param("source", PgValues.text(source))
                .param("payload", jsonb(payload))
                .param("incident", jsonb(incident))
                .update());
    }

    @Override
    public void recordToolCall(ToolCall call) {
        insert("tool_calls", () -> jdbc.sql("""
                INSERT INTO tool_calls (incident_id, tool_name, args, status, duration_ms, evidence_id, error)
                VALUES (:incidentId, :toolName, CAST(:args AS jsonb), :status, :durationMs, :evidenceId, :error)
                """)
                .param("incidentId", PgValues.text(call.incidentId()))
                .param("toolName", call.toolName())
                .param("args", jsonb(call.args()))
                .param("status", call.status())
                .param("durationMs", call.durationMs())
                .param("evidenceId", call.evidenceIds() == null || call.evidenceIds().isEmpty()
                        ? null : String.join(",", call.evidenceIds()))
                .param("error", PgValues.text(call.error()))
                .update());
    }

    @Override
    public void recordLlmExchange(LlmExchange exchange) {
        insert("llm_exchanges", () -> jdbc.sql("""
                INSERT INTO llm_exchanges (incident_id, model, request, response, input_tokens, output_tokens,
                                           latency_ms)
                VALUES (:incidentId, :model, CAST(:request AS jsonb), CAST(:response AS jsonb), :inputTokens,
                        :outputTokens, :latencyMs)
                """)
                .param("incidentId", PgValues.text(exchange.incidentId()))
                .param("model", PgValues.text(exchange.model()))
                .param("request", PgValues.jsonbText(limit(exchange.requestJson())))
                .param("response", PgValues.jsonbText(limit(exchange.responseJson())))
                .param("inputTokens", exchange.inputTokens())
                .param("outputTokens", exchange.outputTokens())
                .param("latencyMs", exchange.latencyMs())
                .update());
    }

    /** Inserts the report as the next version after the stored one (version 2 or later). */
    @Override
    public void recordReport(InvestigationReport report) {
        insert("reports", () -> jdbc.sql("""
                INSERT INTO reports (incident_id, version, status, body, record)
                SELECT :incidentId, GREATEST(COALESCE(MAX(version), :stored), :stored) + 1, :status,
                       CAST(:body AS jsonb), CAST(:record AS json)
                  FROM reports WHERE incident_id = :incidentId
                """)
                .param("incidentId", report.incidentId())
                .param("stored", JdbcReportRepository.STORED_VERSION)
                .param("status", report.status() == null ? null : report.status().name())
                .param("body", jsonb(report))
                .param("record", PgValues.json(codec, report))
                .update());
    }

    @Override
    public void recordApproval(Approval approval) {
        insert("approvals", () -> jdbc.sql("""
                INSERT INTO approvals (incident_id, action, status, requested_at, decided_by, decided_at)
                VALUES (:incidentId, :action, :status, CAST(:requestedAt AS timestamptz), :decidedBy,
                        CAST(:decidedAt AS timestamptz))
                """)
                .param("incidentId", approval.incidentId())
                .param("action", PgValues.text(approval.action()))
                .param("status", approval.status())
                .param("requestedAt", PgValues.timestamp(approval.requestedAt()))
                .param("decidedBy", PgValues.text(approval.decidedBy()))
                .param("decidedAt", PgValues.timestamp(approval.decidedAt()))
                .update());
    }

    /**
     * Stored as a {@code tool_calls} row with tool name {@value #DRAFT_REJECTION_TOOL} and status
     * {@code rejected}, so no new table or grant is needed; {@code args} holds the attempt, the errors and
     * the next step (CP-77).
     */
    @Override
    public void recordDraftRejection(DraftRejection rejection) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("attempt", rejection.attempt());
        args.put("next", rejection.next());
        args.put("errors", rejection.errors());
        insert("tool_calls", () -> jdbc.sql("""
                INSERT INTO tool_calls (incident_id, tool_name, args, status, duration_ms, evidence_id, error)
                VALUES (:incidentId, :toolName, CAST(:args AS jsonb), 'rejected', 0, NULL, :error)
                """)
                .param("incidentId", PgValues.text(rejection.incidentId()))
                .param("toolName", DRAFT_REJECTION_TOOL)
                .param("args", jsonb(args))
                .param("error", "report draft failed validation; next: " + rejection.next())
                .update());
    }

    private void insert(String table, Runnable statement) {
        try {
            statement.run();
        } catch (RuntimeException e) {
            // Never propagate: the audit trail must not break the investigation (CP-49).
            log.warn("audit insert failed table={} error={}", table, e.getClass().getSimpleName(), e);
        }
    }

    private String jsonb(Object value) {
        return value == null ? null : PgValues.jsonbText(limit(PgValues.json(codec, value)));
    }

    /** {@code json}, or a JSON object holding its first {@value #MAX_JSON_CHARS} characters. */
    String limit(String json) {
        if (json == null || json.length() <= MAX_JSON_CHARS) {
            return json;
        }
        Map<String, Object> truncated = new LinkedHashMap<>();
        truncated.put("truncated", true);
        truncated.put("length", json.length());
        truncated.put("prefix", json.substring(0, MAX_JSON_CHARS));
        return PgValues.json(codec, truncated);
    }
}
