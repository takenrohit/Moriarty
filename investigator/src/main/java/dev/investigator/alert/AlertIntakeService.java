package dev.investigator.alert;

import dev.investigator.api.AlertAcceptedResponse;
import dev.investigator.api.MockAlertRequest;
import dev.investigator.domain.Incident;
import dev.investigator.domain.InvestigationStatus;
import dev.investigator.store.IncidentRepository;
import org.springframework.stereotype.Service;

/**
 * Accepts a validated Mock_Alert, normalizes it into an Incident, stores it, and answers with the new
 * Incident_ID (Req 4.2, 5.1).
 *
 * <p>An alert the {@link AlertNormalizer} rejects throws {@link AlertValidationException} before
 * anything is stored (Req 4.5 to 4.7). Task 12.1 hands the stored Incident to the
 * Investigation_Orchestrator before responding (Req 4.4).
 */
@Service
public class AlertIntakeService {

    private final AlertNormalizer normalizer;
    private final IncidentRepository incidents;

    public AlertIntakeService(AlertNormalizer normalizer, IncidentRepository incidents) {
        this.normalizer = normalizer;
        this.incidents = incidents;
    }

    public AlertAcceptedResponse accept(MockAlertRequest request) {
        Incident incident = normalizer.normalize(request);
        incidents.save(incident);
        return new AlertAcceptedResponse(incident.id(), InvestigationStatus.PENDING, reportUrl(incident.id()));
    }

    /** Relative URL of the report endpoint for {@code incidentId}. */
    public static String reportUrl(String incidentId) {
        return "/api/v1/incidents/" + incidentId + "/report";
    }
}
