package dev.investigator.alert;

import java.util.List;
import java.util.Objects;

/**
 * Thrown by {@link AlertNormalizer} when a Mock_Alert passes bean validation but a value cannot be
 * normalized, for example an unknown severity or a timestamp without a zone offset (Req 4.6, 4.7).
 *
 * <p>Messages describe the expected format and never contain the rejected value.
 */
public class AlertValidationException extends RuntimeException {

    private final List<Violation> violations;

    /** One rejected field. */
    public AlertValidationException(String field, String message) {
        this(List.of(new Violation(field, message)));
    }

    /** Several rejected fields, reported together. */
    public AlertValidationException(List<Violation> violations) {
        super(describe(violations));
        this.violations = List.copyOf(violations);
    }

    /** The rejected fields, in the order they were checked; never empty. */
    public List<Violation> violations() {
        return violations;
    }

    private static String describe(List<Violation> violations) {
        Objects.requireNonNull(violations, "violations");
        if (violations.isEmpty()) {
            throw new IllegalArgumentException("violations must not be empty");
        }
        return violations.stream().map(v -> v.field() + ": " + v.message()).toList().toString();
    }

    /**
     * One rejected field.
     *
     * @param field request field name, for example {@code severity}
     * @param message what the field must contain
     */
    public record Violation(String field, String message) {

        public Violation {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(message, "message");
        }
    }
}
