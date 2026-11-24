package dev.investigator.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** A normalized alert (architecture §4). */
public record Incident(
        String id,
        String title,
        Severity severity,
        String affectedService,
        Instant detectedAt,
        String alertName,
        Map<String, String> labels,
        List<String> dashboardLinks,
        String fingerprint,
        IncidentStatus status) {

    public Incident {
        labels = Copies.map(labels);
        dashboardLinks = Copies.list(dashboardLinks);
    }
}
