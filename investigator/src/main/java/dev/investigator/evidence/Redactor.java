package dev.investigator.evidence;

import java.util.Map;

/**
 * Redaction seam applied to every Evidence payload before it is stored (Req 9.9, CP-8).
 *
 * <p>Phase 1 ships {@link PassThroughRedactor}; Phase 3 (NFR-5) replaces the bean with a real
 * implementation without changing {@link EvidenceService}.
 */
public interface Redactor {

    /**
     * Returns the payload to store and the redaction categories applied to it.
     *
     * @param payload the summary converted to a JSON-compatible map; implementations must not modify it
     */
    RedactionResult redact(Map<String, Object> payload);
}
