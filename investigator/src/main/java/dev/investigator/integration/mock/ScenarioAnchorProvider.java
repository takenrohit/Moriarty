package dev.investigator.integration.mock;

import java.time.Instant;

/** Supplies the Scenario_Anchor and scenario for the mock integrations, which receive no Incident_ID. */
public interface ScenarioAnchorProvider {

    Instant currentAnchor();

    /** The scenario to serve; the default payment-gateway scenario unless overridden. */
    default ScenarioKind currentScenario() {
        return ScenarioKind.DEFAULT;
    }
}
