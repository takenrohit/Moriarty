package dev.investigator.store;

import dev.investigator.domain.Evidence;
import java.util.List;
import java.util.Optional;

/** Stores Evidence (Req 6.1, 9.1). */
public interface EvidenceRepository {

    /**
     * Inserts new Evidence.
     *
     * @throws IllegalStateException if Evidence with the same ID is already stored
     */
    void save(Evidence evidence);

    /** Evidence of one Incident in insertion order; empty if there is none. */
    List<Evidence> findByIncident(String incidentId);

    Optional<Evidence> find(String evidenceId);
}
