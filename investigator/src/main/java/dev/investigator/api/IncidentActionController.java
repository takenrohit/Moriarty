package dev.investigator.api;

import dev.investigator.domain.Investigation;
import dev.investigator.domain.InvestigationStatus;
import dev.investigator.investigation.InvestigationOrchestrator;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Actions on an existing Incident. {@code POST /api/v1/incidents/{id}/reinvestigate} starts a fresh
 * investigation and returns 202 with its status (Req 9.1); 404 for a malformed or unknown ID (Req 9.2),
 * 409 while an investigation is {@code PENDING}/{@code RUNNING} (Req 9.3), 429 when the service's
 * investigation bucket is empty (Req 6.2). The new report becomes the latest version (Req 9.4).
 */
@RestController
@RequestMapping("/api/v1/incidents/{id}")
public class IncidentActionController {

    private final InvestigationOrchestrator orchestrator;

    public IncidentActionController(InvestigationOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping(value = "/reinvestigate", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ReinvestigationAccepted reinvestigate(@PathVariable String id) {
        if (!IncidentController.isIncidentId(id)) {
            throw new IncidentNotFoundException(id);
        }
        Investigation investigation = orchestrator.reinvestigate(id)
                .orElseThrow(() -> new IncidentNotFoundException(id));
        return new ReinvestigationAccepted(id, investigation.status(), "/api/v1/incidents/" + id + "/report");
    }

    /** 202 body: the new Investigation's status and where its report will appear. */
    public record ReinvestigationAccepted(String incidentId, InvestigationStatus status, String reportUrl) {
    }
}
