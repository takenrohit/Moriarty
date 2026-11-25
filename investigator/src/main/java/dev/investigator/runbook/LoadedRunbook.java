package dev.investigator.runbook;

import java.util.Objects;

/**
 * A runbook together with the file it was loaded from.
 *
 * @param fileName file name under {@code runbooks/}, for example {@code payment-gateway-degradation.md};
 *     used for the tie-break (Req 12.6) and the Evidence citation
 */
public record LoadedRunbook(String fileName, Runbook runbook) {

    public LoadedRunbook {
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(runbook, "runbook");
    }
}
