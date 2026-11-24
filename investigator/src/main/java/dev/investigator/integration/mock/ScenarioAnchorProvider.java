package dev.investigator.integration.mock;

import java.time.Instant;

/** Supplies the Scenario_Anchor for the mock integrations, which receive no Incident_ID. */
public interface ScenarioAnchorProvider {

    Instant currentAnchor();
}
