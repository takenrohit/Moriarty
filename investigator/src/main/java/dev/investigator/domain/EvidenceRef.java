package dev.investigator.domain;

import java.time.Instant;

/** Evidence reference listed in the report (FR-20, CP-2). */
public record EvidenceRef(
        String id,
        EvidenceType type,
        String source,
        Instant observedAt,
        String headline,
        String citation) {
}
