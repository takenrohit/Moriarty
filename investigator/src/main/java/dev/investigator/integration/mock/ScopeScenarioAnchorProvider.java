package dev.investigator.integration.mock;

import dev.investigator.config.InvestigatorProperties;
import dev.investigator.investigation.InvestigationContext;
import dev.investigator.investigation.InvestigationScope;
import java.time.Instant;

/**
 * Returns the anchor of the {@link InvestigationScope} bound to the calling thread, or
 * {@code investigator.mock.default-anchor} (the demo {@code detectedAt}) when no scope is bound.
 * Registered by {@code IntegrationConfig}.
 */
public class ScopeScenarioAnchorProvider implements ScenarioAnchorProvider {

    private final Instant defaultAnchor;

    public ScopeScenarioAnchorProvider(InvestigatorProperties properties) {
        this.defaultAnchor = properties.mock().defaultAnchor();
    }

    @Override
    public Instant currentAnchor() {
        return InvestigationScope.current().map(InvestigationContext::anchor).orElse(defaultAnchor);
    }
}
