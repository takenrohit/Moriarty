package dev.investigator.evidence;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.List;

/**
 * Tool_Result of {@code findProblemTraces} (Req 10.5).
 *
 * @param truncated true when more traces matched than the trace limit allowed, so the counts are
 *     lower bounds (added to the design's field list, see CP-33)
 * @param topFailingSpans error spans grouped by service, operation and peer service, count descending
 * @param exampleTraceIds up to the configured number (default 3) of trace IDs, error traces first
 */
public record TraceSummary(
        String evidenceId,
        String service,
        Instant appliedStart,
        Instant appliedEnd,
        boolean clamped,
        int errorTraceCount,
        int slowTraceCount,
        boolean truncated,
        List<FailingSpan> topFailingSpans,
        List<String> exampleTraceIds,
        @JsonIgnore String headline) implements ToolSummary {

    public TraceSummary {
        topFailingSpans = topFailingSpans == null ? List.of() : List.copyOf(topFailingSpans);
        exampleTraceIds = exampleTraceIds == null ? List.of() : List.copyOf(exampleTraceIds);
        headline = Headlines.normalize(headline);
    }

    /** A group of error spans; {@code peerService} is null for server spans. */
    public record FailingSpan(String service, String operation, String peerService, int errorCount) {
    }
}
