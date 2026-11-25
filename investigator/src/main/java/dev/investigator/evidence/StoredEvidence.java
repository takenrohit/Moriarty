package dev.investigator.evidence;

import dev.investigator.domain.Evidence;
import java.util.Objects;

/**
 * Result of {@link EvidenceService#store}: the saved Evidence plus the {@link RedactionResult#status()}
 * the Redactor reported for it, which the tool log entry needs (Req 18.3). The status is not part of
 * the Evidence itself.
 *
 * @param evidence the saved Evidence
 * @param redactionStatus redaction status for logs, for example {@link RedactionResult#NOT_APPLIED}
 */
public record StoredEvidence(Evidence evidence, String redactionStatus) {

    public StoredEvidence {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(redactionStatus, "redactionStatus");
    }
}
