package dev.investigator.api;

import dev.investigator.alert.AlertIntakeService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Alert_Intake_API: {@code POST /api/v1/alerts} (Req 4.1, 4.2). */
@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private final AlertIntakeService intake;

    public AlertController(AlertIntakeService intake) {
        this.intake = intake;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AlertAcceptedResponse> receive(@Valid @RequestBody MockAlertRequest request) {
        return ResponseEntity.accepted().body(intake.accept(request));
    }
}
