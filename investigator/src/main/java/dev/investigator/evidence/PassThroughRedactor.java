package dev.investigator.evidence;

import java.util.Map;
import java.util.Set;

/**
 * No-op {@link Redactor}: returns the payload unchanged with no categories and status
 * {@code not_applied} (CP-8). No longer a bean since {@link RegexRedactor} is the active redactor
 * (CP-76); kept for tests that construct an {@link EvidenceService} without redaction.
 */
public class PassThroughRedactor implements Redactor {

    @Override
    public RedactionResult redact(Map<String, Object> payload) {
        return new RedactionResult(payload, Set.of(), RedactionResult.NOT_APPLIED);
    }
}
