package dev.investigator.evidence;

import dev.investigator.integration.metrics.MetricPoint;
import dev.investigator.integration.metrics.MetricSeries;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Builds the {@link MetricSummary} of a range query (Req 9.5, 10.3).
 *
 * <ul>
 *   <li>Baseline: median of the values in the first quarter of the points (at least one point).
 *   <li>Change point: the first point {@code i} where points {@code i..min(i+2, last)} all differ
 *       from the baseline by more than {@code max(0.5·|baseline|, 1e-9)}.
 *   <li>{@code valueAfterChange}: median of the points from the change point on.
 *   <li>Empty series: {@code pointCount = 0} and null statistics.
 * </ul>
 */
@Component
public class MetricSummarizer {

    static final double MIN_THRESHOLD = 1e-9;

    public MetricSummary summarize(String evidenceId, String service, String query, Instant appliedStart,
            Instant appliedEnd, boolean clamped, MetricSeries series) {
        List<MetricPoint> points = series.points();
        int n = points.size();
        if (n == 0) {
            return new MetricSummary(evidenceId, service, query, appliedStart, appliedEnd, clamped,
                    series.step(), 0, null, null, null, null, null, service + " metric: no data points");
        }
        double[] values = points.stream().mapToDouble(MetricPoint::value).toArray();
        double min = Arrays.stream(values).min().orElseThrow();
        double max = Arrays.stream(values).max().orElseThrow();
        double baseline = median(values, 0, Math.max(1, n / 4));
        double threshold = Math.max(0.5 * Math.abs(baseline), MIN_THRESHOLD);

        int changeIndex = -1;
        for (int i = 0; i < n && changeIndex < 0; i++) {
            int last = Math.min(i + 2, n - 1);
            boolean allDiffer = true;
            for (int j = i; j <= last; j++) {
                if (!(Math.abs(values[j] - baseline) > threshold)) {
                    allDiffer = false;
                    break;
                }
            }
            if (allDiffer) {
                changeIndex = i;
            }
        }

        Instant changePointAt = changeIndex < 0 ? null : points.get(changeIndex).at();
        Double valueAfterChange = changeIndex < 0 ? null : median(values, changeIndex, n);
        String headline = changeIndex < 0
                ? service + " metric steady at " + Headlines.number(baseline) + " (min "
                        + Headlines.number(min) + ", max " + Headlines.number(max) + ", " + n + " points)"
                : service + " metric changed from " + Headlines.number(baseline) + " to "
                        + Headlines.number(valueAfterChange) + " at " + changePointAt;
        return new MetricSummary(evidenceId, service, query, appliedStart, appliedEnd, clamped, series.step(),
                n, min, max, baseline, changePointAt, valueAfterChange, headline);
    }

    /** Median of {@code values[from, to)}; the mean of the two middle values for an even count. */
    static double median(double[] values, int from, int to) {
        double[] sorted = Arrays.copyOfRange(values, from, to);
        Arrays.sort(sorted);
        int mid = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2.0;
    }
}
