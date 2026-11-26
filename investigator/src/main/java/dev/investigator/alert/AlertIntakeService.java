package dev.investigator.alert;

import dev.investigator.api.AlertAcceptedResponse;
import dev.investigator.api.MockAlertRequest;
import dev.investigator.api.WebhookAcceptedResponse;
import dev.investigator.dedup.DedupService;
import dev.investigator.domain.Incident;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Accepts a validated Mock_Alert or normalized webhook Incidents and passes each through the
 * {@link DedupService}, answering with the Incident_ID (Req 4.2, 4.4, 5.1, 11.1, 11.2).
 *
 * <p>An alert the {@link AlertNormalizer} rejects throws {@link AlertValidationException} before
 * anything is stored (Req 4.5 to 4.7). A new Incident is stored, audited with the alert it came from
 * (Req 12.5) and handed to the orchestrator, which returns without waiting for the agent (Req 4.3). A
 * repeat alert matching an open Incident is audited against that Incident and answered with its ID and
 * current Investigation status; no new investigation starts (Req 11.1, 11.4).
 */
@Service
public class AlertIntakeService {

    private final AlertNormalizer normalizer;
    private final DedupService dedup;

    public AlertIntakeService(AlertNormalizer normalizer, DedupService dedup) {
        this.normalizer = normalizer;
        this.dedup = dedup;
    }

    public AlertAcceptedResponse accept(MockAlertRequest request) {
        Incident incident = normalizer.normalize(request);
        DedupService.Admission admission = dedup.admit(incident, "mock", request);
        return new AlertAcceptedResponse(admission.incidentId(), admission.status(),
                reportUrl(admission.incidentId()));
    }

    /**
     * Admits already-normalized webhook Incidents one by one and returns their IDs (new or existing) in
     * order (Req 8.2, 9.2, 11.1).
     *
     * @param source  alert source recorded in the audit trail, for example {@code alertmanager}
     * @param payload the webhook payload the Incidents were normalized from
     */
    public WebhookAcceptedResponse acceptAll(String source, Object payload, List<Incident> normalized) {
        List<String> ids = normalized.stream()
                .map(incident -> dedup.admit(incident, source, payload).incidentId())
                .toList();
        return new WebhookAcceptedResponse(ids);
    }

    /** Relative URL of the report endpoint for {@code incidentId}. */
    public static String reportUrl(String incidentId) {
        return "/api/v1/incidents/" + incidentId + "/report";
    }
}
