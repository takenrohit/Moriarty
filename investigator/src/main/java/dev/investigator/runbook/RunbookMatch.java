package dev.investigator.runbook;

import java.util.List;
import java.util.Objects;

/**
 * A runbook selected by {@link RunbookCatalog}, reduced to what {@code findRunbook} returns.
 *
 * @param fileName file name under {@code runbooks/}, used as the Evidence citation
 * @param steps the extracted steps (list items of the body, or the start of the body)
 */
public record RunbookMatch(String fileName, String title, String service, List<String> steps) {

    public RunbookMatch {
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(service, "service");
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
