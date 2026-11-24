package dev.investigator.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * One stored tool result or the alert itself (architecture §4, CP-2).
 *
 * @param summary   capped JSON Tool_Result shown to the model
 * @param headline  one-line human description (at most 200 characters), added by CP-2
 * @param payload   redacted structured data
 */
public record Evidence(
        String id,
        String incidentId,
        EvidenceType type,
        String source,
        Instant observedAt,
        String summary,
        String headline,
        Map<String, Object> payload,
        String citation,
        Set<String> redactions) {

    public Evidence {
        payload = Copies.map(payload);
        redactions = Copies.set(redactions);
    }
}
