package dev.investigator.api;

/** Thrown when a request names an Incident_ID that is not in the Incident_Store; mapped to 404 (Req 6.8). */
public class IncidentNotFoundException extends RuntimeException {

    public IncidentNotFoundException(String incidentId) {
        super("incident not found: " + incidentId);
    }
}
