package dev.investigator.integration.logs;

import java.time.Instant;

/** Read-only access to a log backend such as Loki (Req 8.1, 10.2). */
public interface LogClient {

    /**
     * Searches logs over {@code [start, end]}, returning at most {@code limit} entries and never more
     * than the configured fetch limit (default 500, Req 11.14).
     */
    LogSearchResult search(String service, String logql, Instant start, Instant end, int limit);
}
