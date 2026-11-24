package dev.investigator.investigation;

import java.util.Optional;

/** Holds the {@link InvestigationContext} of each investigation that has not finished yet. */
public interface InvestigationContextRegistry {

    /** Registers {@code ctx} under its Incident_ID, replacing any earlier context for that ID. */
    void register(InvestigationContext ctx);

    Optional<InvestigationContext> find(String incidentId);

    /** Removes the context for {@code incidentId}; does nothing when none is registered. */
    void remove(String incidentId);
}
