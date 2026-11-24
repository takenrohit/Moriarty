package dev.investigator.alert;

import dev.investigator.api.MockAlertRequest;
import dev.investigator.domain.Incident;
import dev.investigator.domain.IncidentStatus;
import dev.investigator.domain.Severity;
import dev.investigator.store.IncidentRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Alert_Normalizer: converts a Mock_Alert into an {@code OPEN} Incident (Req 5.1 to 5.5).
 *
 * <ul>
 *   <li>{@code severity}: trimmed and matched case-insensitively against {@link Severity} (Req 4.6).
 *   <li>{@code detectedAt}: {@link OffsetDateTime#parse} then {@link OffsetDateTime#toInstant}, so any
 *       offset becomes the equivalent UTC instant; a timestamp without an offset is rejected
 *       (Req 4.7, 5.4).
 *   <li>Title: {@code <alert> on <service>} (Req 5.2).
 *   <li>ID: from {@link IncidentIdGenerator}, retried while the Incident_Store already holds it (Req 5.3).
 *   <li>Fingerprint: {@link Fingerprints#of} (Req 5.5).
 * </ul>
 *
 * <p>The request normally arrives bean-validated; the normalizer still rejects missing required
 * values and null list or map entries, so it never builds a partial Incident. Every problem found is
 * reported in one {@link AlertValidationException}; messages never contain the rejected value.
 */
@Component
public class AlertNormalizer {

    static final String SEVERITY_MESSAGE = "must be one of critical, high, warning, info";
    static final String DETECTED_AT_MESSAGE =
            "must be an ISO-8601 timestamp with a zone offset, for example 2026-11-23T14:40:00Z";
    static final String REQUIRED_MESSAGE = "must not be blank";
    static final String NULL_LABEL_MESSAGE = "label keys and values must not be null";
    static final String NULL_LINK_MESSAGE = "must not be null";

    private final IncidentIdGenerator ids;
    private final IncidentRepository incidents;

    public AlertNormalizer(IncidentIdGenerator ids, IncidentRepository incidents) {
        this.ids = Objects.requireNonNull(ids, "ids");
        this.incidents = Objects.requireNonNull(incidents, "incidents");
    }

    /**
     * Returns the normalized Incident with a fresh Incident_ID. Does not store it.
     *
     * @throws AlertValidationException naming every field that cannot be normalized
     */
    public Incident normalize(MockAlertRequest request) {
        Objects.requireNonNull(request, "request");
        List<AlertValidationException.Violation> violations = new ArrayList<>();

        requireText("alert", request.alert(), violations);
        requireText("service", request.service(), violations);
        Severity severity = parseSeverity(request.severity(), violations);
        Instant detectedAt = parseDetectedAt(request.detectedAt(), violations);
        checkLabels(request.labels(), violations);
        checkLinks(request.dashboardLinks(), violations);

        if (!violations.isEmpty()) {
            throw new AlertValidationException(violations);
        }

        String id = ids.next(candidate -> incidents.find(candidate).isPresent());
        return new Incident(
                id,
                request.alert() + " on " + request.service(),
                severity,
                request.service(),
                detectedAt,
                request.alert(),
                request.labels(),
                request.dashboardLinks(),
                Fingerprints.of(request.alert(), request.service(), request.labels()),
                IncidentStatus.OPEN);
    }

    private static void requireText(String field, String value, List<AlertValidationException.Violation> out) {
        if (value == null || value.isBlank()) {
            out.add(new AlertValidationException.Violation(field, REQUIRED_MESSAGE));
        }
    }

    private static Severity parseSeverity(String value, List<AlertValidationException.Violation> out) {
        if (value == null || value.isBlank()) {
            out.add(new AlertValidationException.Violation("severity", REQUIRED_MESSAGE));
            return null;
        }
        try {
            return Severity.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            out.add(new AlertValidationException.Violation("severity", SEVERITY_MESSAGE));
            return null;
        }
    }

    private static Instant parseDetectedAt(String value, List<AlertValidationException.Violation> out) {
        if (value == null || value.isBlank()) {
            out.add(new AlertValidationException.Violation("detectedAt", REQUIRED_MESSAGE));
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            out.add(new AlertValidationException.Violation("detectedAt", DETECTED_AT_MESSAGE));
            return null;
        }
    }

    private static void checkLabels(Map<String, String> labels, List<AlertValidationException.Violation> out) {
        if (labels != null && labels.entrySet().stream()
                .anyMatch(label -> label.getKey() == null || label.getValue() == null)) {
            out.add(new AlertValidationException.Violation("labels", NULL_LABEL_MESSAGE));
        }
    }

    private static void checkLinks(List<String> links, List<AlertValidationException.Violation> out) {
        if (links == null) {
            return;
        }
        for (int i = 0; i < links.size(); i++) {
            if (links.get(i) == null) {
                out.add(new AlertValidationException.Violation("dashboardLinks[" + i + "]", NULL_LINK_MESSAGE));
            }
        }
    }
}
