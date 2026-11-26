package dev.investigator.store;

import dev.investigator.domain.Incident;
import dev.investigator.domain.IncidentStatus;
import java.util.List;
import java.util.Optional;

/** Stores Incidents (Req 6.1). */
public interface IncidentRepository {

    /**
     * Inserts a new Incident.
     *
     * @throws IllegalStateException if an Incident with the same ID is already stored
     */
    void save(Incident incident);

    Optional<Incident> find(String id);

    /** All Incidents, newest {@code detectedAt} first; ties ordered by ID. */
    List<Incident> findAllNewestFirst();

    /**
     * Every open Incident with this Fingerprint, newest {@code detectedAt} first (ties ordered by ID), used by
     * deduplication (Req 11.1, CP-50). "Open" means status {@code OPEN} or {@code INVESTIGATING}; a
     * {@code REPORTED} or {@code RESOLVED} Incident is not open, so a repeat alert after its report starts a
     * fresh investigation. More than one can be returned when an Incident's Investigation ended but the
     * Incident never became {@code REPORTED}, so the caller decides which one is still being investigated.
     */
    List<Incident> findOpenByFingerprint(String fingerprint);

    /**
     * Replaces the status of a stored Incident.
     *
     * @throws java.util.NoSuchElementException if no Incident has this ID
     */
    void updateStatus(String id, IncidentStatus status);
}
