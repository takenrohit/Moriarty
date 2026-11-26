package dev.investigator.api;

import dev.investigator.alert.AlertIntakeService;
import dev.investigator.alert.AlertmanagerNormalizer;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webhook_Intake_API for Alertmanager: {@code POST /api/v1/alerts/alertmanager} with a v4 payload
 * (Req 8.1 to 8.6). Answers 202 with one Incident_ID per firing alert; investigations run asynchronously.
 */
@RestController
public class AlertmanagerController {

    private final AlertmanagerNormalizer normalizer;
    private final AlertIntakeService intake;

    public AlertmanagerController(AlertmanagerNormalizer normalizer, AlertIntakeService intake) {
        this.normalizer = normalizer;
        this.intake = intake;
    }

    @PostMapping(path = "/api/v1/alerts/alertmanager",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<WebhookAcceptedResponse> receive(@RequestBody AlertmanagerPayload payload) {
        return ResponseEntity.accepted().body(intake.acceptAll("alertmanager", payload, normalizer.normalize(payload)));
    }
}
