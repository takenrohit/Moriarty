package dev.investigator.api;

import dev.investigator.alert.AlertIntakeService;
import dev.investigator.alert.GrafanaNormalizer;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webhook_Intake_API for Grafana: {@code POST /api/v1/alerts/grafana} with a unified-alerting webhook
 * payload (Req 9.1 to 9.5). Answers 202 with one Incident_ID per firing alert; investigations run
 * asynchronously.
 */
@RestController
public class GrafanaController {

    private final GrafanaNormalizer normalizer;
    private final AlertIntakeService intake;

    public GrafanaController(GrafanaNormalizer normalizer, AlertIntakeService intake) {
        this.normalizer = normalizer;
        this.intake = intake;
    }

    @PostMapping(path = "/api/v1/alerts/grafana",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<WebhookAcceptedResponse> receive(@RequestBody GrafanaPayload payload) {
        return ResponseEntity.accepted().body(intake.acceptAll(normalizer.normalize(payload)));
    }
}
