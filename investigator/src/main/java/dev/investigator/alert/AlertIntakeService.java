package dev.investigator.alert;

import dev.investigator.api.AlertAcceptedResponse;
import dev.investigator.api.MockAlertRequest;
import dev.investigator.api.WebhookAcceptedResponse;
import dev.investigator.domain.Incident;
import dev.investigator.domain.Investigation;
import dev.investigator.investigation.InvestigationOrchestrator;
import dev.investigator.store.IncidentRepository;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Accepts a validated Mock_Alert, normalizes it into an Incident, stores it, hands it to the
 * Investigation_Orchestrator and answers with the new Incident_ID (Req 4.2, 4.4, 5.1).
 *
 * <p>An alert the {@link AlertNormalizer} rejects throws {@link AlertValidationException} before
 * anything is stored (Req 4.5 to 4.7). {@link InvestigationOrchestrator#start} creates the
 * {@code PENDING} Investigation and returns without waiting for the agent (Req 4.3).
 */
@Service
public class AlertIntakeService {

    private final AlertNormalizer normalizer;
    private final IncidentRepository incidents;
    private final InvestigationOrchestrator orchestrator;

    public AlertIntakeService(AlertNormalizer normalizer, IncidentRepository incidents,
            InvestigationOrchestrator orchestrator) {
        this.normalizer = normalizer;
        this.incidents = incidents;
        this.orchestrator = orchestrator;
    }

    public AlertAcceptedResponse accept(MockAlertRequest request) {
        Incident incident = normalizer.normalize(request);
        incidents.save(incident);
        Investigation investigation = orchestrator.start(incident);
        return new AlertAcceptedResponse(incident.id(), investigation.status(), reportUrl(incident.id()));
    }

    /**
     * Stores already-normalized webhook Incidents, starts one investigation per Incident and returns their
     * IDs in order (Req 8.2, 9.2). Deduplication by fingerprint is added in plan 2.7.
     */
    public WebhookAcceptedResponse acceptAll(List<Incident> normalized) {
        normalized.forEach(incidents::save);
        normalized.forEach(orchestrator::start);
        return new WebhookAcceptedResponse(normalized.stream().map(Incident::id).toList());
    }

    /** Relative URL of the report endpoint for {@code incidentId}. */
    public static String reportUrl(String incidentId) {
        return "/api/v1/incidents/" + incidentId + "/report";
    }
}
