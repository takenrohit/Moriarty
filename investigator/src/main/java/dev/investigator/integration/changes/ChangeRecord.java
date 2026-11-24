package dev.investigator.integration.changes;

import java.time.Instant;
import java.util.List;

/** One deployment of a service, with the files its change touched (CP-9). */
public record ChangeRecord(
        String service,
        String version,
        Instant deployedAt,
        String author,
        String prTitle,
        String link,
        List<String> changedFiles) {

    public ChangeRecord {
        changedFiles = changedFiles == null ? List.of() : List.copyOf(changedFiles);
    }
}
