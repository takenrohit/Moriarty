package dev.investigator.integration.traces;

import java.util.List;

/**
 * The result of a problem-trace search.
 *
 * @param traces matching traces in start-time order, at most the requested limit
 * @param truncated true when more traces matched than the limit allowed
 */
public record TraceSearchResult(List<TraceRecord> traces, boolean truncated) {

    public TraceSearchResult {
        traces = traces == null ? List.of() : List.copyOf(traces);
    }
}
