package dev.investigator.logging;

/**
 * Names of the structured log fields (Req 18.2, 18.3).
 *
 * <p>{@link #INCIDENT_ID} and {@link #TRACE_ID} are MDC keys set on the investigation threads; the
 * others are SLF4J key-value pairs on the tool log entry. With
 * {@code logging.structured.format.console: logstash} both kinds become top-level JSON fields.
 */
public final class LogFields {

    /** Incident_ID of the running investigation (MDC). */
    public static final String INCIDENT_ID = "incident_id";

    /** 32-hex trace ID of the running investigation (MDC). */
    public static final String TRACE_ID = "trace_id";

    /** Name of the tool that was called. */
    public static final String TOOL_NAME = "tool_name";

    /** Evidence_ID stored by the tool call; comma-separated when the call stored several. */
    public static final String EVIDENCE_ID = "evidence_id";

    /** Redaction status of the stored Evidence, for example {@code not_applied}. */
    public static final String REDACTION_STATUS = "redaction_status";

    /** Duration of the tool call in milliseconds. */
    public static final String DURATION_MS = "duration_ms";

    /** Outcome of the tool call: {@code success}, {@code rejected} or {@code error}. */
    public static final String OUTCOME = "outcome";

    private LogFields() {
    }
}
