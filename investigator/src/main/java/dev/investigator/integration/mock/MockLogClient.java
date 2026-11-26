package dev.investigator.integration.mock;

import dev.investigator.config.InvestigatorProperties;
import dev.investigator.integration.logs.LogClient;
import dev.investigator.integration.logs.LogSearchResult;
import java.time.Instant;

/**
 * {@link LogClient} backed by {@link MockScenario}; applies {@code |= "text"} line filters and returns
 * at most {@code min(limit, investigator.tools.log-fetch-limit)} entries, setting {@code truncated} when
 * more matched (Req 11.14). Registered by {@code IntegrationConfig} when no real log client is active.
 */
public class MockLogClient implements LogClient {

    private final ScenarioAnchorProvider anchors;
    private final int fetchLimit;

    public MockLogClient(ScenarioAnchorProvider anchors, InvestigatorProperties properties) {
        this.anchors = anchors;
        this.fetchLimit = properties.tools().logFetchLimit();
    }

    @Override
    public LogSearchResult search(String service, String logql, Instant start, Instant end, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        return MockScenario.forAnchor(anchors.currentAnchor())
                .logs(service, logql, start, end, Math.min(limit, fetchLimit));
    }
}
