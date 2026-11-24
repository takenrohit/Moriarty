package dev.investigator.integration.logs;

import java.util.List;

/**
 * The result of a log search.
 *
 * @param entries matching entries in time order, at most the requested limit
 * @param truncated true when more entries matched than the limit allowed
 */
public record LogSearchResult(List<LogEntry> entries, boolean truncated) {

    public LogSearchResult {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
