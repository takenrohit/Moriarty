// Feature: real-integrations, Property 1: Prometheus range-query parsing preserves samples
package dev.investigator.integration.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.investigator.testsupport.PropertySeeds;
import dev.investigator.testsupport.ResponseGenerators;
import dev.investigator.testsupport.ResponseGenerators.PromMatrix;
import dev.investigator.testsupport.ResponseGenerators.PromSample;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Property 1 over {@link PrometheusMetricsClient#parse}: every finite sample of a single-series matrix
 * becomes one point with the same timestamp and value, points are in time order, {@code NaN}/{@code ±Inf}
 * samples are skipped, and at most {@code maxPoints} points are kept (the earliest ones).
 */
class PrometheusParsingPropertyTest {

    /** Reads bodies as {@code PrometheusMetricsClient} does: decimals exactly, so sub-second times survive. */
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private static final String QUERY = "sum(rate(http_server_requests_seconds_count{service=\"checkout-api\"}[5m]))";

    /** **Validates: Requirements 3.3, 3.7** */
    @ParameterizedTest(name = "seed={0}")
    @MethodSource("dev.investigator.testsupport.PropertySeeds#seeds")
    void parsingPreservesEveryFiniteSampleInTimeOrder(long seed) {
        PropertySeeds.check(seed, r -> {
            int maxPoints = r.nextInt(4) == 0 ? 300 : r.nextInt(2, 301);
            Duration step = Duration.ofSeconds(r.nextInt(1, 121));
            PromMatrix matrix = ResponseGenerators.prometheusMatrix(r, maxPoints);

            MetricSeries series = PrometheusMetricsClient.parse(JSON.readTree(matrix.body()), QUERY, step, maxPoints);

            List<MetricPoint> expected = matrix.samples().stream()
                    .filter(PromSample::finite)
                    .sorted(Comparator.comparing(PromSample::at))
                    .limit(maxPoints)
                    .map(s -> new MetricPoint(s.at(), s.value()))
                    .toList();
            assertThat(series.query()).isEqualTo(QUERY);
            assertThat(series.step()).isEqualTo(step);
            assertThat(series.points()).hasSizeLessThanOrEqualTo(maxPoints)
                    .isSortedAccordingTo(Comparator.comparing(MetricPoint::at))
                    .containsExactlyElementsOf(expected);
        });
    }
}
