package dev.investigator.integration.mock;

import dev.investigator.integration.changes.ChangeClient;
import dev.investigator.integration.changes.ChangeRecord;
import java.time.Instant;
import java.util.List;

/**
 * {@link ChangeClient} backed by {@link MockScenario}. Registered by {@code IntegrationConfig} when no
 * real change client is active.
 */
public class MockChangeClient implements ChangeClient {

    private final ScenarioAnchorProvider anchors;

    public MockChangeClient(ScenarioAnchorProvider anchors) {
        this.anchors = anchors;
    }

    @Override
    public List<ChangeRecord> recentChanges(String service, Instant since, Instant until) {
        return MockScenario.forAnchor(anchors.currentScenario(), anchors.currentAnchor()).changes(service, since, until);
    }
}
