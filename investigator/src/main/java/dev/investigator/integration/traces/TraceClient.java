package dev.investigator.integration.traces;

import java.time.Duration;
import java.time.Instant;

/** Read-only access to a trace backend such as Tempo (Req 8.1, 10.2). */
public interface TraceClient {

    /**
     * Finds traces starting in {@code [start, end]} that have an error or last at least
     * {@code slowThreshold}, returning at most {@code limit} traces and never more than the configured
     * trace limit (default 50, Req 11.14).
     */
    TraceSearchResult findProblemTraces(String service, Instant start, Instant end, Duration slowThreshold, int limit);
}
