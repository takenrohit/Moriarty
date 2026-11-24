package dev.investigator.evidence;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Duration;
import java.time.Instant;

/**
 * Tool_Result of {@code getMetric} (Req 10.3). Statistics are null when the series is empty;
 * {@code changePointAt} and {@code valueAfterChange} are null when no change point was found.
 *
 * @param step the step actually used, written as an ISO-8601 duration such as {@code PT15S}
 */
public record MetricSummary(
        String evidenceId,
        String service,
        String query,
        Instant appliedStart,
        Instant appliedEnd,
        boolean clamped,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Duration step,
        int pointCount,
        Double min,
        Double max,
        Double baseline,
        Instant changePointAt,
        Double valueAfterChange,
        @JsonIgnore String headline) implements ToolSummary {

    public MetricSummary {
        headline = Headlines.normalize(headline);
    }
}
