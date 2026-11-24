package dev.investigator.domain;

import java.util.List;

/** Ordered timeline of an incident (architecture §4). */
public record IncidentTimeline(List<TimelineEntry> entries) {

    public IncidentTimeline {
        entries = Copies.list(entries);
    }
}
