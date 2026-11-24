package dev.investigator.evidence;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.List;

/**
 * Tool_Result of {@code searchLogs} (Req 10.4). {@code firstSeen} and {@code lastSeen} are null when
 * nothing matched.
 *
 * @param matchCount number of fetched entries
 * @param truncated true when more entries matched than the fetch limit allowed
 * @param topMessages the most frequent trimmed messages, count descending then message ascending
 */
public record LogSummary(
        String evidenceId,
        String service,
        String query,
        Instant appliedStart,
        Instant appliedEnd,
        boolean clamped,
        int matchCount,
        boolean truncated,
        List<MessageCount> topMessages,
        Instant firstSeen,
        Instant lastSeen,
        @JsonIgnore String headline) implements ToolSummary {

    public LogSummary {
        topMessages = topMessages == null ? List.of() : List.copyOf(topMessages);
        headline = Headlines.normalize(headline);
    }

    /** One distinct message (at most 300 code points) and how many fetched entries carried it. */
    public record MessageCount(String message, int count) {
    }
}
