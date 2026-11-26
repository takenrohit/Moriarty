package dev.investigator.api;

import java.util.List;

/**
 * 202 response of the Alertmanager and Grafana webhook endpoints (Req 8.2, 8.4, 9.2, 9.4).
 *
 * @param incidentIds one Incident_ID per firing alert, in payload order; empty when every alert was
 *     resolved
 */
public record WebhookAcceptedResponse(List<String> incidentIds) {
    public WebhookAcceptedResponse {
        incidentIds = List.copyOf(incidentIds);
    }
}
