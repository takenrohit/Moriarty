package dev.investigator.integration.changes;

import java.time.Instant;
import java.util.List;

/** Read-only access to deployment history such as GitHub releases (Req 8.1, 10.2). */
public interface ChangeClient {

    /** Returns the deployments of {@code service} in {@code [since, until]}, newest first. */
    List<ChangeRecord> recentChanges(String service, Instant since, Instant until);
}
