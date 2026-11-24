package dev.investigator.runbook;

import java.util.Optional;
import org.springframework.stereotype.Component;

/** Placeholder {@link RunbookCatalog} with no runbooks; removed by task 16.1. */
@Component
public class EmptyRunbookCatalog implements RunbookCatalog {

    @Override
    public Optional<RunbookMatch> bestMatch(String service, String symptom) {
        return Optional.empty();
    }
}
