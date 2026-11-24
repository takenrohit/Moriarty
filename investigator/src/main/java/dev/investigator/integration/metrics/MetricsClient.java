package dev.investigator.integration.metrics;

import java.time.Duration;
import java.time.Instant;

/** Read-only access to a metrics backend such as Prometheus (Req 8.1, 10.2). */
public interface MetricsClient {

    /**
     * Runs a range query over {@code [start, end]}.
     *
     * <p>Implementations return at most the configured maximum number of points (default 300) and
     * enlarge {@code step} when needed to stay within it (Req 11.14).
     */
    MetricSeries queryRange(String service, String promql, Instant start, Instant end, Duration step);
}
