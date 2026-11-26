package dev.investigator.store;

import dev.investigator.domain.Investigation;
import dev.investigator.domain.InvestigationStatus;
import java.util.Optional;

/** Stores one Investigation per Incident, keyed by Incident_ID (Req 6.1, 6.3, 6.4). */
public interface InvestigationRepository {

    /**
     * Inserts a new Investigation.
     *
     * @throws IllegalArgumentException if its status is not {@code PENDING}
     * @throws IllegalStateException if the Incident already has an Investigation
     */
    void create(Investigation investigation);

    Optional<Investigation> find(String incidentId);

    /**
     * Atomically replaces the Incident's Investigation with {@code fresh} if the current one is terminal
     * ({@code COMPLETED}, {@code INCONCLUSIVE} or {@code FAILED}), for a re-investigation (Req 9.1, 9.3).
     *
     * @return false, changing nothing, if the Incident has no Investigation or it is still
     *     {@code PENDING}/{@code RUNNING}
     * @throws IllegalArgumentException if {@code fresh} is not {@code PENDING}
     */
    boolean restart(Investigation fresh);

    /**
     * Atomically moves the Investigation from {@code from} to {@code to}.
     *
     * @return true if the status was {@code from} and the pair is allowed by
     *     {@link dev.investigator.investigation.StatusTransitions}; false otherwise, in which case
     *     nothing changes (disallowed pair, stale {@code from}, or unknown Incident)
     */
    boolean transition(String incidentId, InvestigationStatus from, InvestigationStatus to);

    /**
     * Records the number of tool calls used so far.
     *
     * @throws java.util.NoSuchElementException if the Incident has no Investigation
     */
    void recordToolCalls(String incidentId, int used);
}
