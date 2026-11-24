package dev.investigator.alert;

import dev.investigator.api.AlertAcceptedResponse;
import dev.investigator.api.MockAlertRequest;
import dev.investigator.domain.InvestigationStatus;
import org.springframework.stereotype.Service;

/**
 * Accepts a validated Mock_Alert and answers with the new Incident_ID (Req 4.2).
 *
 * <p>Task 7.1 adds normalization and storage of the Incident (and uses the Incident_Store for the
 * ID collision check); task 12.1 hands the Incident to the Investigation_Orchestrator before
 * responding (Req 4.4).
 */
@Service
public class AlertIntakeService {

    private final IncidentIdGenerator ids;

    public AlertIntakeService(IncidentIdGenerator ids) {
        this.ids = ids;
    }

    public AlertAcceptedResponse accept(MockAlertRequest request) {
        // No Incident_Store exists yet, so no ID can collide; task 7.1 passes the repository lookup.
        String incidentId = ids.next(candidate -> false);
        return new AlertAcceptedResponse(incidentId, InvestigationStatus.PENDING, reportUrl(incidentId));
    }

    /** Relative URL of the report endpoint for {@code incidentId}. */
    public static String reportUrl(String incidentId) {
        return "/api/v1/incidents/" + incidentId + "/report";
    }
}
