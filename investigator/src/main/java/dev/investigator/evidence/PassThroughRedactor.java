package dev.investigator.evidence;

import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Phase 1 {@link Redactor}: returns the payload unchanged with no categories and status
 * {@code not_applied} (CP-8). Real redaction is NFR-5 / plan 3.5.
 */
@Component
public class PassThroughRedactor implements Redactor {

    @Override
    public RedactionResult redact(Map<String, Object> payload) {
        return new RedactionResult(payload, Set.of(), RedactionResult.NOT_APPLIED);
    }
}
