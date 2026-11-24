package dev.investigator.integration.traces;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** One trace with its spans. */
public record TraceRecord(String traceId, Instant start, Duration duration, boolean error, List<SpanRecord> spans) {

    public TraceRecord {
        spans = spans == null ? List.of() : List.copyOf(spans);
    }
}
