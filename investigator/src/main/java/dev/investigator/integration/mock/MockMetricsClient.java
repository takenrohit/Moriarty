package dev.investigator.integration.mock;

import dev.investigator.config.InvestigatorProperties;
import dev.investigator.integration.metrics.MetricSeries;
import dev.investigator.integration.metrics.MetricsClient;
import java.time.Duration;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * {@link MetricsClient} backed by {@link MockScenario}; returns at most
 * {@code investigator.tools.max-metric-points} points, enlarging the step when needed (Req 11.14).
 */
@Component
@Profile("mock")
public class MockMetricsClient implements MetricsClient {

    private final ScenarioAnchorProvider anchors;
    private final int maxPoints;

    public MockMetricsClient(ScenarioAnchorProvider anchors, InvestigatorProperties properties) {
        this.anchors = anchors;
        this.maxPoints = properties.tools().maxMetricPoints();
    }

    @Override
    public MetricSeries queryRange(String service, String promql, Instant start, Instant end, Duration step) {
        return MockScenario.forAnchor(anchors.currentAnchor()).metric(service, promql, start, end, step, maxPoints);
    }
}
