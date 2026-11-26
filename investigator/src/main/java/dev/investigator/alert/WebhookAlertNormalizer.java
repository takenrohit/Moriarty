package dev.investigator.alert;

import dev.investigator.api.HttpUrlValidator;
import dev.investigator.config.WebhookProperties;
import dev.investigator.domain.Incident;
import dev.investigator.domain.IncidentStatus;
import dev.investigator.domain.Severity;
import dev.investigator.store.IncidentRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Shared mapping of Alertmanager-style webhook alerts (Alertmanager v4 and Grafana unified alerting)
 * to {@code OPEN} Incidents (Req 8.2 to 8.5, 9.2 to 9.5, CP-52, CP-53).
 *
 * <ul>
 *   <li>Only {@code firing} alerts become Incidents; the source's acknowledged statuses (for example
 *       {@code resolved}) are skipped; any other status is rejected.
 *   <li>Alert name: label {@code alertname}. Service: the configured label
 *       ({@link WebhookProperties#serviceLabel()}, default {@code service}). Missing either gives 400
 *       naming the label.
 *   <li>Severity: label {@code severity}, case-insensitive; missing or unknown values become
 *       {@link Severity#WARNING} so an alert is never dropped for its severity wording.
 *   <li>{@code detectedAt}: the alert's {@code startsAt} (ISO-8601 with offset).
 *   <li>Labels: copied through unchanged; the Fingerprint is {@link Fingerprints#of} over alert name,
 *       service and those labels.
 *   <li>Dashboard links: the source links, then absolute http(s) annotation values in key order, without
 *       duplicates, at most {@link #MAX_LINKS}.
 * </ul>
 *
 * <p>Every alert is checked before any Incident is built, so a rejected payload creates nothing. Error
 * messages name the field and never contain request values.
 */
@Component
public class WebhookAlertNormalizer {

    static final String FIRING = "firing";
    static final int MAX_LINKS = 10;
    static final String ALERT_NAME_LABEL = "alertname";
    static final String SEVERITY_LABEL = "severity";
    static final String MISSING_LABEL_MESSAGE = "required label is missing or blank";
    static final String STATUS_MESSAGE_PREFIX = "must be one of ";
    static final String STARTS_AT_MESSAGE =
            "must be an ISO-8601 timestamp with a zone offset, for example 2026-11-23T14:40:00Z";
    static final String REQUIRED_MESSAGE = "must not be null";
    static final String NULL_LABEL_MESSAGE = "label values must not be null";

    private final IncidentIdGenerator ids;
    private final IncidentRepository incidents;
    private final String serviceLabel;

    public WebhookAlertNormalizer(IncidentIdGenerator ids, IncidentRepository incidents, WebhookProperties webhook) {
        this.ids = Objects.requireNonNull(ids, "ids");
        this.incidents = Objects.requireNonNull(incidents, "incidents");
        this.serviceLabel = webhook.serviceLabel();
    }

    /**
     * Returns one Incident per firing alert, in payload order, with fresh Incident_IDs. Does not store them.
     *
     * @param alerts the payload's alerts; {@code null} is rejected
     * @param acknowledgedStatuses lowercase statuses that are accepted without an Incident
     * @throws AlertValidationException naming every field that cannot be normalized
     */
    public List<Incident> normalize(List<WebhookAlert> alerts, Set<String> acknowledgedStatuses) {
        if (alerts == null) {
            throw new AlertValidationException("alerts", REQUIRED_MESSAGE);
        }
        List<AlertValidationException.Violation> violations = new ArrayList<>();
        List<WebhookAlert> firing = new ArrayList<>();
        List<Instant> startTimes = new ArrayList<>();
        for (int i = 0; i < alerts.size(); i++) {
            String prefix = "alerts[" + i + "]";
            WebhookAlert alert = alerts.get(i);
            if (alert == null) {
                violations.add(new AlertValidationException.Violation(prefix, REQUIRED_MESSAGE));
                continue;
            }
            String status = alert.status() == null ? "" : alert.status().trim().toLowerCase(Locale.ROOT);
            if (acknowledgedStatuses.contains(status)) {
                continue;
            }
            if (!status.equals(FIRING)) {
                violations.add(new AlertValidationException.Violation(prefix + ".status",
                        STATUS_MESSAGE_PREFIX + String.join(", ", allowed(acknowledgedStatuses))));
                continue;
            }
            Map<String, String> labels = alert.labels() == null ? Map.of() : alert.labels();
            if (labels.values().stream().anyMatch(Objects::isNull)) {
                violations.add(new AlertValidationException.Violation(prefix + ".labels", NULL_LABEL_MESSAGE));
                continue;
            }
            requireLabel(labels, ALERT_NAME_LABEL, prefix, violations);
            requireLabel(labels, serviceLabel, prefix, violations);
            Instant startsAt = parseStartsAt(alert.startsAt(), prefix, violations);
            firing.add(alert);
            startTimes.add(startsAt);
        }
        if (!violations.isEmpty()) {
            throw new AlertValidationException(violations);
        }
        Set<String> issued = new HashSet<>();
        List<Incident> result = new ArrayList<>(firing.size());
        for (int i = 0; i < firing.size(); i++) {
            WebhookAlert alert = firing.get(i);
            Map<String, String> labels = alert.labels();
            String alertName = labels.get(ALERT_NAME_LABEL);
            String service = labels.get(serviceLabel);
            String id = ids.next(candidate -> issued.contains(candidate) || incidents.find(candidate).isPresent());
            issued.add(id);
            result.add(new Incident(
                    id,
                    alertName + " on " + service,
                    severity(labels.get(SEVERITY_LABEL)),
                    service,
                    startTimes.get(i),
                    alertName,
                    labels,
                    links(alert),
                    Fingerprints.of(alertName, service, labels),
                    IncidentStatus.OPEN));
        }
        return List.copyOf(result);
    }

    private static List<String> allowed(Set<String> acknowledgedStatuses) {
        List<String> allowed = new ArrayList<>();
        allowed.add(FIRING);
        acknowledgedStatuses.stream().sorted().forEach(allowed::add);
        return allowed;
    }

    private static void requireLabel(Map<String, String> labels, String name, String prefix,
            List<AlertValidationException.Violation> out) {
        String value = labels.get(name);
        if (value == null || value.isBlank()) {
            out.add(new AlertValidationException.Violation(prefix + ".labels." + name, MISSING_LABEL_MESSAGE));
        }
    }

    private static Instant parseStartsAt(String value, String prefix, List<AlertValidationException.Violation> out) {
        if (value == null || value.isBlank()) {
            out.add(new AlertValidationException.Violation(prefix + ".startsAt", STARTS_AT_MESSAGE));
            return null;
        }
        try {
            return OffsetDateTime.parse(value.trim()).toInstant();
        } catch (DateTimeParseException e) {
            out.add(new AlertValidationException.Violation(prefix + ".startsAt", STARTS_AT_MESSAGE));
            return null;
        }
    }

    private static Severity severity(String value) {
        if (value == null) {
            return Severity.WARNING;
        }
        try {
            return Severity.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Severity.WARNING;
        }
    }

    private static List<String> links(WebhookAlert alert) {
        Set<String> links = new LinkedHashSet<>();
        if (alert.links() != null) {
            alert.links().stream().filter(HttpUrlValidator::isHttpUrl).forEach(links::add);
        }
        if (alert.annotations() != null) {
            new TreeMap<>(alert.annotations()).values().stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(HttpUrlValidator::isHttpUrl)
                    .forEach(links::add);
        }
        return links.stream().limit(MAX_LINKS).toList();
    }

    /**
     * One alert of a webhook payload, reduced to what the mapping reads.
     *
     * @param status alert status, for example {@code firing} or {@code resolved}
     * @param labels alert labels, including {@code alertname}
     * @param annotations alert annotations; absolute http(s) values become dashboard links
     * @param startsAt ISO-8601 start (evaluation) time
     * @param links source links in preference order (generator, dashboard, panel); may contain nulls
     */
    public record WebhookAlert(String status, Map<String, String> labels, Map<String, String> annotations,
            String startsAt, List<String> links) {
    }
}
