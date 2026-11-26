package dev.investigator.evidence;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Output of the {@link Redactor}.
 *
 * @param payload the payload to store and to send to the model
 * @param categories redaction categories applied, for example {@code email}; empty when nothing was
 *     redacted (Req 9.8)
 * @param status redaction status for logs, for example {@link #NOT_APPLIED}
 */
public record RedactionResult(Map<String, Object> payload, Set<String> categories, String status) {

    /** Status when nothing was redacted, and always from {@link PassThroughRedactor}. */
    public static final String NOT_APPLIED = "not_applied";

    /** Status when {@link RegexRedactor} replaced at least one value (Req 5.2). */
    public static final String APPLIED = "applied";

    public RedactionResult {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(status, "status");
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        categories = categories == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(categories));
    }
}
