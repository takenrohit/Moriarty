package dev.investigator.alert;

import dev.investigator.api.GrafanaPayload;
import dev.investigator.domain.Incident;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Maps a Grafana unified-alerting webhook payload to one {@code OPEN} Incident per firing alert; resolved
 * or ok alerts are acknowledged without an Incident (Req 9.2 to 9.5, CP-53). The rule name is the
 * {@code alertname} label and {@code detectedAt} the alert's {@code startsAt} evaluation time; the other
 * rules are in {@link WebhookAlertNormalizer}. Dashboard links: {@code dashboardURL}, {@code panelURL},
 * {@code generatorURL}, then annotation URLs.
 */
@Component
public class GrafanaNormalizer {

    static final Set<String> ACKNOWLEDGED_STATUSES = Set.of("resolved", "ok");

    private final WebhookAlertNormalizer normalizer;

    public GrafanaNormalizer(WebhookAlertNormalizer normalizer) {
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer");
    }

    /**
     * Returns the Incidents for the payload's firing alerts, with fresh Incident_IDs. Does not store them.
     *
     * @throws AlertValidationException naming every field that cannot be normalized
     */
    public List<Incident> normalize(GrafanaPayload payload) {
        Objects.requireNonNull(payload, "payload");
        List<WebhookAlertNormalizer.WebhookAlert> alerts = payload.alerts() == null ? null
                : payload.alerts().stream().map(GrafanaNormalizer::toWebhookAlert).toList();
        return normalizer.normalize(alerts, ACKNOWLEDGED_STATUSES);
    }

    private static WebhookAlertNormalizer.WebhookAlert toWebhookAlert(GrafanaPayload.GrafanaAlert alert) {
        if (alert == null) {
            return null;
        }
        List<String> links = Stream.of(alert.dashboardURL(), alert.panelURL(), alert.generatorURL())
                .filter(Objects::nonNull)
                .toList();
        return new WebhookAlertNormalizer.WebhookAlert(alert.status(), alert.labels(), alert.annotations(),
                alert.startsAt(), links);
    }
}
