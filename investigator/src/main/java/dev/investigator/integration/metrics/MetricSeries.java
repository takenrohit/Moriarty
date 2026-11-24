package dev.investigator.integration.metrics;

import java.time.Duration;
import java.util.List;

/**
 * The result of a range query.
 *
 * @param query the query as executed
 * @param step the step actually used, which may be larger than the requested step (Req 11.14)
 * @param points samples in time order
 */
public record MetricSeries(String query, Duration step, List<MetricPoint> points) {

    public MetricSeries {
        points = points == null ? List.of() : List.copyOf(points);
    }
}
