package dev.investigator.evidence;

import dev.investigator.config.InvestigatorProperties;
import dev.investigator.integration.traces.SpanRecord;
import dev.investigator.integration.traces.TraceRecord;
import dev.investigator.integration.traces.TraceSearchResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Builds the {@link TraceSummary} of a problem-trace search (Req 9.5, 10.5).
 *
 * <ul>
 *   <li>Failing spans: error spans grouped by {@code (service, operation, peerService)}, sorted by
 *       count descending then key ascending (nulls first), top {@value #TOP_FAILING_SPANS} kept.
 *   <li>Slow traces: duration at least the slow threshold (default 2 s), matching the
 *       {@code TraceClient} contract.
 *   <li>Example trace IDs: error traces first, then the others, in result order, up to the configured
 *       number (default 3).
 * </ul>
 */
@Component
public class TraceSummarizer {

    static final int TOP_FAILING_SPANS = 5;

    private static final Comparator<String> NULLS_FIRST = Comparator.nullsFirst(Comparator.naturalOrder());
    private static final Comparator<SpanKey> KEY_ORDER = Comparator.comparing(SpanKey::service, NULLS_FIRST)
            .thenComparing(SpanKey::operation, NULLS_FIRST)
            .thenComparing(SpanKey::peerService, NULLS_FIRST);

    private final int exampleCount;
    private final Duration slowThreshold;

    @Autowired
    public TraceSummarizer(InvestigatorProperties properties) {
        this(properties.tools().traceExamples(), properties.tools().slowTraceThreshold());
    }

    public TraceSummarizer(int exampleCount, Duration slowThreshold) {
        if (exampleCount < 0) {
            throw new IllegalArgumentException("exampleCount must not be negative");
        }
        this.exampleCount = exampleCount;
        this.slowThreshold = Objects.requireNonNull(slowThreshold, "slowThreshold");
    }

    public TraceSummary summarize(String evidenceId, String service, Instant appliedStart, Instant appliedEnd,
            boolean clamped, TraceSearchResult result) {
        List<TraceRecord> traces = result.traces();
        int errorCount = 0;
        int slowCount = 0;
        Map<SpanKey, Integer> spanCounts = new LinkedHashMap<>();
        List<String> errorIds = new ArrayList<>();
        List<String> otherIds = new ArrayList<>();
        for (TraceRecord trace : traces) {
            if (trace.error()) {
                errorCount++;
                errorIds.add(trace.traceId());
            } else {
                otherIds.add(trace.traceId());
            }
            if (trace.duration() != null && trace.duration().compareTo(slowThreshold) >= 0) {
                slowCount++;
            }
            for (SpanRecord span : trace.spans()) {
                if (span.error()) {
                    spanCounts.merge(new SpanKey(span.service(), span.operation(), span.peerService()), 1, Integer::sum);
                }
            }
        }
        List<TraceSummary.FailingSpan> top = spanCounts.entrySet().stream()
                .sorted(Map.Entry.<SpanKey, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey(KEY_ORDER)))
                .limit(TOP_FAILING_SPANS)
                .map(e -> new TraceSummary.FailingSpan(
                        e.getKey().service(), e.getKey().operation(), e.getKey().peerService(), e.getValue()))
                .toList();
        List<String> examples = new ArrayList<>(errorIds);
        examples.addAll(otherIds);
        examples = examples.subList(0, Math.min(examples.size(), exampleCount));

        String suffix = result.truncated() ? "+" : "";
        StringBuilder headline = new StringBuilder(service).append(" traces: ")
                .append(errorCount).append(suffix).append(" with errors, ")
                .append(slowCount).append(suffix).append(" slow");
        if (!top.isEmpty()) {
            TraceSummary.FailingSpan first = top.get(0);
            headline.append("; top failing span ").append(first.service()).append(' ').append(first.operation());
            if (first.peerService() != null) {
                headline.append(" -> ").append(first.peerService());
            }
            headline.append(" (").append(first.errorCount()).append(')');
        }
        return new TraceSummary(evidenceId, service, appliedStart, appliedEnd, clamped, errorCount, slowCount,
                result.truncated(), top, examples, headline.toString());
    }

    private record SpanKey(String service, String operation, String peerService) {
    }
}
