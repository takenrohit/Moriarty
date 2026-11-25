package dev.investigator.runbook;

import java.util.Optional;

/**
 * Finds the runbook that best matches a service and symptom for {@code findRunbook} (Req 10.7, 12.6).
 *
 * <p>{@link RunbookService} is the Markdown-backed implementation.
 */
public interface RunbookCatalog {

    /** The best-scoring runbook for {@code service} and {@code symptom}, or empty when none matches. */
    Optional<RunbookMatch> bestMatch(String service, String symptom);
}
