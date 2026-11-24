package dev.investigator.integration.mock;

import dev.investigator.config.InvestigatorProperties;
import dev.investigator.integration.traces.TraceClient;
import dev.investigator.integration.traces.TraceSearchResult;
import java.time.Duration;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * {@link TraceClient} backed by {@link MockScenario}; returns at most
 * {@code min(limit, investigator.tools.trace-limit)} traces, setting {@code truncated} when more matched
 * (Req 11.14).
 */
@Component
@Profile("mock")
public class MockTraceClient implements TraceClient {

    private final ScenarioAnchorProvider anchors;
    private final int traceLimit;

    public MockTraceClient(ScenarioAnchorProvider anchors, InvestigatorProperties properties) {
        this.anchors = anchors;
        this.traceLimit = properties.tools().traceLimit();
    }

    @Override
    public TraceSearchResult findProblemTraces(String service, Instant start, Instant end, Duration slowThreshold,
            int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        return MockScenario.forAnchor(anchors.currentAnchor())
                .traces(service, start, end, slowThreshold, Math.min(limit, traceLimit));
    }
}
