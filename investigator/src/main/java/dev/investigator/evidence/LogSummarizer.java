package dev.investigator.evidence;

import dev.investigator.config.InvestigatorProperties;
import dev.investigator.integration.logs.LogEntry;
import dev.investigator.integration.logs.LogSearchResult;
import dev.investigator.integration.logs.LogTemplater;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Builds the {@link LogSummary} of a log search (Req 9.5, 10.4): trimmed messages grouped by their
 * {@link LogTemplater} template (Req 4.4), each group shown as its first message in entry order, sorted by
 * count descending then message ascending, top N (default 5) kept, each shown with at most
 * {@value #MAX_MESSAGE_LENGTH} code points.
 */
@Component
public class LogSummarizer {

    static final int MAX_MESSAGE_LENGTH = 300;

    private final int topMessages;

    @Autowired
    public LogSummarizer(InvestigatorProperties properties) {
        this(properties.tools().logTopMessages());
    }

    public LogSummarizer(int topMessages) {
        if (topMessages < 1) {
            throw new IllegalArgumentException("topMessages must be at least 1");
        }
        this.topMessages = topMessages;
    }

    public LogSummary summarize(String evidenceId, String service, String query, Instant appliedStart,
            Instant appliedEnd, boolean clamped, LogSearchResult result) {
        List<LogEntry> entries = result.entries();
        // Template -> first message of that template (in entry order) and its count.
        Map<String, String> representatives = new HashMap<>();
        Map<String, Integer> templateCounts = new HashMap<>();
        Instant firstSeen = null;
        Instant lastSeen = null;
        for (LogEntry entry : entries) {
            String message = entry.message() == null ? "" : entry.message().trim();
            String template = LogTemplater.template(message);
            representatives.putIfAbsent(template, message);
            templateCounts.merge(template, 1, Integer::sum);
            Instant at = entry.at();
            if (at != null) {
                firstSeen = firstSeen == null || at.isBefore(firstSeen) ? at : firstSeen;
                lastSeen = lastSeen == null || at.isAfter(lastSeen) ? at : lastSeen;
            }
        }
        Map<String, Integer> counts = new HashMap<>();
        templateCounts.forEach((template, count) -> counts.put(representatives.get(template), count));
        List<LogSummary.MessageCount> top = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(topMessages)
                .map(e -> new LogSummary.MessageCount(Headlines.truncate(e.getKey(), MAX_MESSAGE_LENGTH), e.getValue()))
                .toList();

        String headline = entries.isEmpty()
                ? service + " logs: no matches"
                : service + " logs: " + entries.size() + (result.truncated() ? "+" : "") + " matches, top \""
                        + top.get(0).message() + "\" (" + top.get(0).count() + ")";
        return new LogSummary(evidenceId, service, query, appliedStart, appliedEnd, clamped, entries.size(),
                result.truncated(), top, firstSeen, lastSeen, headline);
    }
}
