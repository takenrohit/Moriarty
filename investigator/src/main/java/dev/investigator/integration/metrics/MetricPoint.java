package dev.investigator.integration.metrics;

import java.time.Instant;

/** One sample of a metric series. */
public record MetricPoint(Instant at, double value) {
}
