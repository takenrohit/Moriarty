package dev.investigator.api;

import dev.investigator.domain.InvestigationStatus;

/**
 * 202 response of {@code POST /api/v1/alerts} (Req 4.2).
 *
 * @param incidentId the new Incident_ID
 * @param status the Investigation status at the time of the response
 * @param reportUrl relative URL of the report endpoint
 */
public record AlertAcceptedResponse(String incidentId, InvestigationStatus status, String reportUrl) {
}
