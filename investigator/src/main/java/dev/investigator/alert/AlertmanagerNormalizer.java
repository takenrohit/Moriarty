package dev.investigator.alert;

import dev.investigator.api.AlertmanagerPayload;
import dev.investigator.domain.Incident;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Maps an Alertmanager v4 webhook payload to one {@code OPEN} Incident per firing alert; resolved alerts
 * are acknowledged without an Incident (Req 8.2 to 8.5, CP-52, CP-53). The rules are in
 * {@link WebhookAlertNormalizer}; the alert's {@code generatorURL} is the first dashboard link.
 */
@Component
public class AlertmanagerNormalizer {

    static final Set<String> ACKNOWLEDGED_STATUSES = Set.of("resolved");

    private final WebhookAlertNormalizer normalizer;

    public AlertmanagerNormalizer(WebhookAlertNormalizer normalizer) {
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer");
    }

    /**
     * Returns the Incidents for the payload's firing alerts, with fresh Incident_IDs. Does not store them.
     *
     * @throws AlertValidationException naming every field that cannot be normalized
     */
    public List<Incident> normalize(AlertmanagerPayload payload) {
        Objects.requireNonNull(payload, "payload");
        List<WebhookAlertNormalizer.WebhookAlert> alerts = payload.alerts() == null ? null
                : payload.alerts().stream().map(AlertmanagerNormalizer::toWebhookAlert).toList();
        return normalizer.normalize(alerts, ACKNOWLEDGED_STATUSES);
    }

    private static WebhookAlertNormalizer.WebhookAlert toWebhookAlert(AlertmanagerPayload.AlertmanagerAlert alert) {
        if (alert == null) {
            return null;
        }
        return new WebhookAlertNormalizer.WebhookAlert(alert.status(), alert.labels(), alert.annotations(),
                alert.startsAt(), Stream.of(alert.generatorURL()).filter(Objects::nonNull).toList());
    }
}
