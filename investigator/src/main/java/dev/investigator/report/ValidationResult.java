package dev.investigator.report;

import java.util.List;

/**
 * Outcome of a {@link ReportValidator} check: every violation found, each starting with the JSON path
 * it concerns. Empty means valid.
 */
public record ValidationResult(List<String> violations) {

    public ValidationResult {
        violations = violations == null ? List.of() : List.copyOf(violations);
    }

    public boolean valid() {
        return violations.isEmpty();
    }
}
