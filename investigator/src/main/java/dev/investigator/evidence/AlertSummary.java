package dev.investigator.evidence;

import com.fasterxml.jackson.annotation.JsonIgnore;
import dev.investigator.domain.Incident;
import dev.investigator.domain.Severity;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Summary of the ALERT Evidence stored when an investigation is created (Req 9.7, CP-1). */
public record AlertSummary(
        String evidenceId,
        String alertName,
        String service,
        Severity severity,
        Instant detectedAt,
        String title,
        Map<String, String> labels,
        List<String> dashboardLinks,
        String fingerprint,
        @JsonIgnore String headline) implements ToolSummary {

    public AlertSummary {
        labels = labels == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(labels));
        dashboardLinks = dashboardLinks == null ? List.of() : List.copyOf(dashboardLinks);
        headline = Headlines.normalize(headline);
    }

    /** Builds the summary with the headline {@code <alertName> fired on <service> (<SEVERITY>)}. */
    public static AlertSummary of(String evidenceId, Incident incident) {
        Objects.requireNonNull(incident, "incident");
        return new AlertSummary(evidenceId, incident.alertName(), incident.affectedService(),
                incident.severity(), incident.detectedAt(), incident.title(), incident.labels(),
                incident.dashboardLinks(), incident.fingerprint(),
                incident.alertName() + " fired on " + incident.affectedService() + " (" + incident.severity() + ")");
    }
}
