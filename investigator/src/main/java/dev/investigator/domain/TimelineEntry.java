package dev.investigator.domain;

import java.time.Instant;
import java.util.List;

/** One timeline event with the evidence that supports it (architecture §4). */
public record TimelineEntry(Instant at, String description, List<String> evidenceIds) {

    public TimelineEntry {
        evidenceIds = Copies.list(evidenceIds);
    }
}
