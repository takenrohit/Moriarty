package dev.investigator.integration.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.investigator.config.IntegrationProperties;
import dev.investigator.config.IntegrationProperties.Endpoint;
import dev.investigator.config.InvestigatorProperties;
import dev.investigator.integration.http.IntegrationCallException;
import dev.investigator.integration.http.RestClients;
import dev.investigator.tools.ObservabilityTools;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link PrometheusMetricsClient} against a real Prometheus container (the image tag used by
 * {@code deploy/docker-compose.yml}) seeded by OpenMetrics backfill: {@code promtool tsdb
 * create-blocks-from openmetrics} writes two hours of {@code http_server_requests_seconds_count} samples
 * for {@code checkout-api} (status 200 and 500, with the 5xx rate jumping 30 minutes before the end) into
 * the TSDB before Prometheus starts. Checks step selection against the point cap, mapping of an
 * aggregated ratio to one series, and the single-series hint for a bare selector (Req 14.3, 14.5, 14.6,
 * 14.7).
 */
@Tag("containers")
@Testcontainers(disabledWithoutDocker = true)
class PrometheusContainerIT {

    private static final String IMAGE = "prom/prometheus:v3.16.0";
    private static final int PORT = 9090;
    private static final Duration SCRAPE = Duration.ofSeconds(15);
    private static final Duration SEEDED = Duration.ofHours(2);
    private static final Duration BEFORE_CHANGE_END = Duration.ofMinutes(30);

    /** Last seeded sample, aligned to the 15 s sample interval. */
    private static final Instant END = Instant.ofEpochSecond(
            Instant.now().getEpochSecond() / SCRAPE.getSeconds() * SCRAPE.getSeconds());
    private static final Instant START = END.minus(SEEDED);
    private static final Instant CHANGE = END.minus(BEFORE_CHANGE_END);

    private static final String METRIC = "http_server_requests_seconds_count";
    private static final String TOTAL = "sum(rate(" + METRIC + "{service=\"checkout-api\"}[5m]))";
    private static final String RATIO = "sum(rate(" + METRIC + "{service=\"checkout-api\",status=~\"5..\"}[5m]))"
            + " / sum(rate(" + METRIC + "{service=\"checkout-api\"}[5m]))";

    private static final InvestigatorProperties PROPERTIES = new Binder(new MapConfigurationPropertySource())
            .bindOrCreate("investigator", InvestigatorProperties.class);

    @Container
    static final GenericContainer<?> PROMETHEUS = new GenericContainer<>(IMAGE)
            .withCopyToContainer(Transferable.of(seed()), "/seed/data.om")
            .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("/bin/sh", "-c",
                    "promtool tsdb create-blocks-from openmetrics /seed/data.om /prometheus"
                            + " && exec /bin/prometheus --config.file=/etc/prometheus/prometheus.yml"
                            + " --storage.tsdb.path=/prometheus"))
            .withExposedPorts(PORT)
            .waitingFor(Wait.forHttp("/-/ready").forPort(PORT).forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(2)));

    @Test
    void longRangeStepKeepsPointsWithinTheCap() {
        int cap = PROPERTIES.tools().maxMetricPoints();
        Duration expectedStep = ObservabilityTools.metricStep(SEEDED, Duration.ofSeconds(1), cap);
        assertThat(expectedStep).isGreaterThan(Duration.ofSeconds(1));

        MetricSeries series = client().queryRange("checkout-api", TOTAL, START, END, Duration.ofSeconds(1));

        assertThat(series.step()).isEqualTo(expectedStep);
        assertThat(series.points()).hasSizeLessThanOrEqualTo(cap).hasSizeGreaterThan(cap / 2);
        List<MetricPoint> points = series.points();
        for (int i = 1; i < points.size(); i++) {
            assertThat(Duration.between(points.get(i - 1).at(), points.get(i).at())).isEqualTo(expectedStep);
        }
        // 10 req/s of status 200 plus 0.01 or 5 req/s of status 500, once the 5m window is full of data.
        assertThat(points).filteredOn(p -> p.at().isAfter(START.plus(Duration.ofMinutes(6))))
                .isNotEmpty().allSatisfy(p -> assertThat(p.value()).isBetween(9.0, 16.0));
    }

    @Test
    void aggregatedRatioMapsToOneSeriesWithTheRise() {
        MetricSeries series = client().queryRange("checkout-api", RATIO, START, END, SCRAPE);

        assertThat(series.query()).isEqualTo(RATIO);
        List<MetricPoint> points = series.points();
        assertThat(points).isNotEmpty().isSortedAccordingTo((a, b) -> a.at().compareTo(b.at()));
        List<MetricPoint> before = points.stream().filter(p -> p.at().isBefore(CHANGE)).toList();
        List<MetricPoint> after = points.stream()
                .filter(p -> p.at().isAfter(CHANGE.plus(Duration.ofMinutes(6)))).toList();
        assertThat(before).isNotEmpty().allSatisfy(p -> assertThat(p.value()).isLessThan(0.01));
        // 5 / (10 + 5) once the 5m window lies wholly after the change point.
        assertThat(after).isNotEmpty().allSatisfy(p -> assertThat(p.value()).isBetween(0.3, 0.37));
    }

    @Test
    void bareMultiSeriesSelectorThrowsWithTheSingleSeriesHint() {
        String bare = METRIC + "{service=\"checkout-api\"}";

        assertThatThrownBy(() -> client().queryRange("checkout-api", bare, START, END, SCRAPE))
                .isInstanceOfSatisfying(IntegrationCallException.class, e -> {
                    assertThat(e.integration()).isEqualTo("metrics");
                    assertThat(e.hint()).isEqualTo(PrometheusMetricsClient.SINGLE_SERIES_HINT);
                })
                .hasMessageContaining("2 series");
    }

    private static PrometheusMetricsClient client() {
        String baseUrl = "http://" + PROMETHEUS.getHost() + ":" + PROMETHEUS.getMappedPort(PORT);
        Endpoint metrics = new Endpoint(baseUrl, null, null, null, Duration.ofSeconds(5), Duration.ofSeconds(30));
        Endpoint unset = new Endpoint(null, null, null, null, Duration.ofSeconds(2), Duration.ofSeconds(10));
        return new PrometheusMetricsClient(
                new RestClients(new IntegrationProperties(metrics, unset, unset, unset)), PROPERTIES);
    }

    /**
     * OpenMetrics text: status 200 grows by 10/s throughout; status 500 by 0.01/s until {@link #CHANGE},
     * then by 5/s. One sample per 15 s per series, each series contiguous and in time order.
     */
    private static String seed() {
        StringBuilder out = new StringBuilder();
        double ok = 0;
        for (Instant t = START; !t.isAfter(END); t = t.plus(SCRAPE)) {
            ok += 10.0 * SCRAPE.getSeconds();
            sample(out, "200", ok, t);
        }
        double errors = 0;
        for (Instant t = START; !t.isAfter(END); t = t.plus(SCRAPE)) {
            errors += (t.isAfter(CHANGE) ? 5.0 : 0.01) * SCRAPE.getSeconds();
            sample(out, "500", errors, t);
        }
        return out.append("# EOF\n").toString();
    }

    private static void sample(StringBuilder out, String status, double value, Instant at) {
        out.append(METRIC).append("{service=\"checkout-api\",status=\"").append(status).append("\"} ")
                .append(value).append(' ').append(at.getEpochSecond()).append('\n');
    }
}
