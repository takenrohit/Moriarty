package dev.investigator.integration.traces;

import java.time.Duration;

/**
 * One span of a trace.
 *
 * @param service the service that emitted the span
 * @param operation the span name, for example {@code POST /charge}
 * @param peerService the called service for client spans, or null for server spans
 * @param duration the span duration
 * @param error whether the span has error status
 */
public record SpanRecord(String service, String operation, String peerService, Duration duration, boolean error) {
}
