package dev.investigator.integration.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.investigator.integration.changes.ChangeRecord;
import dev.investigator.integration.logs.LogEntry;
import dev.investigator.integration.logs.LogSearchResult;
import dev.investigator.integration.metrics.MetricPoint;
import dev.investigator.integration.metrics.MetricSeries;
import dev.investigator.integration.traces.SpanRecord;
import dev.investigator.integration.traces.TraceSearchResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** Demo values of {@code demo-scenario.md} for the demo alert anchor (20:10 IST = 14:40Z). */
class MockScenarioTest {

    private static final Instant A = Instant.parse("2026-11-23T14:40:00Z");
    private static final Instant WINDOW_START = A.minus(Duration.ofMinutes(60));
    private static final Instant WINDOW_END = A.plus(Duration.ofMinutes(10));
    private static final String ERROR_RATIO_QUERY =
            "sum(rate(http_requests_total{service=\"checkout-api\",status=~\"5..\"}[5m]))"
                    + " / sum(rate(http_requests_total{service=\"checkout-api\"}[5m]))";
    private static final String P99_QUERY =
            "histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{service=\"payment-gateway\"}[5m])))";

    private final MockScenario scenario = MockScenario.forAnchor(A);

    @Test
    void checkoutErrorRatioRisesAt2008Ist() {
        MetricSeries series = scenario.metric("checkout-api", ERROR_RATIO_QUERY, WINDOW_START, WINDOW_END,
                Duration.ofMinutes(1), 300);

        assertThat(series.query()).isEqualTo(ERROR_RATIO_QUERY);
        assertThat(series.step()).isEqualTo(Duration.ofMinutes(1));
        assertThat(series.points()).hasSize(71);
        assertThat(valueAt(series, "2026-11-23T14:26:00Z")).isEqualTo(0.002);
        assertThat(valueAt(series, "2026-11-23T14:37:00Z")).isEqualTo(0.002);
        assertThat(valueAt(series, "2026-11-23T14:38:00Z")).isEqualTo(0.12);
        assertThat(valueAt(series, "2026-11-23T14:50:00Z")).isEqualTo(0.12);
    }

    @Test
    void paymentGatewayP99JumpsAt2007Ist() {
        MetricSeries series = scenario.metric("payment-gateway", P99_QUERY, WINDOW_START, WINDOW_END,
                Duration.ofMinutes(1), 300);

        assertThat(valueAt(series, "2026-11-23T14:36:00Z")).isEqualTo(180.0);
        assertThat(valueAt(series, "2026-11-23T14:37:00Z")).isEqualTo(5000.0);
    }

    @Test
    void stepIsEnlargedToStayWithin300Points() {
        MetricSeries series = scenario.metric("checkout-api", "sum(rate(http_requests_total[5m]))",
                A.minus(Duration.ofHours(6)), A, Duration.ofSeconds(15), 300);

        assertThat(series.step()).isEqualTo(Duration.ofSeconds(73));
        assertThat(series.points()).hasSizeLessThanOrEqualTo(300);
        assertThat(series.points()).allSatisfy(p -> assertThat(p.value()).isEqualTo(50.0));
    }

    @Test
    void checkoutLogsHave214TimeoutsFirstAt2008Ist() {
        LogSearchResult result = scenario.logs("checkout-api",
                "{service=\"checkout-api\"} |= \"paymentgatewaytimeoutexception\"", WINDOW_START, WINDOW_END, 500);

        assertThat(result.truncated()).isFalse();
        assertThat(result.entries()).hasSize(214);
        assertThat(result.entries().get(0).at()).isEqualTo(Instant.parse("2026-11-23T14:38:00Z"));
        assertThat(result.entries()).allSatisfy(e -> {
            assertThat(e.level()).isEqualTo("ERROR");
            assertThat(e.message()).isEqualTo(MockScenario.TIMEOUT_MESSAGE);
        });
    }

    @Test
    void logFiltersMatchTheLevelAndMustAllMatch() {
        assertThat(scenario.logs("checkout-api", "{service=\"checkout-api\"} |= \"error\"",
                WINDOW_START, WINDOW_END, 500).entries()).hasSize(214);
        assertThat(scenario.logs("checkout-api", "{service=\"checkout-api\"} |= \"timed out\" |= `checkout completed`",
                WINDOW_START, WINDOW_END, 500).entries()).isEmpty();

        LogSearchResult all = scenario.logs("checkout-api", "{service=\"checkout-api\"}", WINDOW_START, WINDOW_END, 500);
        assertThat(all.entries()).hasSize(214 + 71);
        assertThat(all.entries()).isSortedAccordingTo((x, y) -> x.at().compareTo(y.at()));
    }

    @Test
    void logLimitSetsTruncated() {
        LogSearchResult result = scenario.logs("checkout-api", "|= \"timeout\"", WINDOW_START, WINDOW_END, 10);

        assertThat(result.entries()).hasSize(10);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void failedTracesPointAtPaymentProviderCall() {
        TraceSearchResult result = scenario.traces("checkout-api", WINDOW_START, WINDOW_END, Duration.ofSeconds(2), 50);

        assertThat(result.traces()).hasSize(50);
        assertThat(result.truncated()).isTrue();
        assertThat(result.traces().get(0).start()).isEqualTo(Instant.parse("2026-11-23T14:38:00Z"));
        assertThat(result.traces().get(0).traceId()).matches("[0-9a-f]{32}");
        assertThat(result.traces()).allSatisfy(t -> {
            assertThat(t.error()).isTrue();
            assertThat(t.spans()).contains(new SpanRecord("payment-gateway", "POST /authorize", "payment-provider",
                    Duration.ofMillis(5000), true));
        });
        assertThat(scenario.traces("payment-gateway", WINDOW_START, WINDOW_END, Duration.ofSeconds(2), 100)
                .traces()).hasSize(60);
    }

    @Test
    void deployV2143At1956IstDoesNotTouchThePaymentPath() {
        List<ChangeRecord> changes = scenario.changes("checkout-api", WINDOW_END.minus(Duration.ofHours(24)), WINDOW_END);

        assertThat(changes).hasSize(1);
        ChangeRecord deploy = changes.get(0);
        assertThat(deploy.version()).isEqualTo("v2.14.3");
        assertThat(deploy.deployedAt()).isEqualTo(Instant.parse("2026-11-23T14:26:00Z"));
        assertThat(deploy.author()).isEqualTo("dev-alice");
        assertThat(deploy.prTitle()).isEqualTo("Update order confirmation copy");
        assertThat(deploy.link()).isEqualTo("https://github.com/example/checkout-api/pull/1423");
        assertThat(deploy.changedFiles()).isNotEmpty().allSatisfy(f -> assertThat(f.toLowerCase(Locale.ROOT))
                .doesNotContain("payment", "timeout", "retry"));

        assertThat(scenario.changes("checkout-api", A.minus(Duration.ofHours(30)), A))
                .extracting(ChangeRecord::version).containsExactly("v2.14.3", "v2.14.2");
    }

    @Test
    void otherServicesReturnTheHealthyBaseline() {
        MetricSeries errors = scenario.metric("payment-provider", "sum(rate(http_requests_total{status=~\"5..\"}[5m]))",
                WINDOW_START, WINDOW_END, Duration.ofMinutes(1), 300);
        assertThat(errors.points()).allSatisfy(p -> assertThat(p.value()).isEqualTo(0.002));
        MetricSeries latency = scenario.metric("checkout-api", P99_QUERY, WINDOW_START, WINDOW_END,
                Duration.ofMinutes(1), 300);
        assertThat(latency.points()).allSatisfy(p -> assertThat(p.value()).isEqualTo(120.0));

        assertThat(scenario.logs("payment-provider", "", WINDOW_START, WINDOW_END, 500).entries())
                .extracting(LogEntry::level).containsOnly("INFO");
        assertThat(scenario.traces("payment-provider", WINDOW_START, WINDOW_END, Duration.ofSeconds(2), 50).traces())
                .isEmpty();
        assertThat(scenario.changes("payment-gateway", A.minus(Duration.ofHours(48)), A)).isEmpty();
    }

    @Test
    void resultsAreShiftedWithTheAnchorAndStayInRange() {
        Duration d = Duration.ofDays(3).plusSeconds(7);
        MockScenario shifted = MockScenario.forAnchor(A.plus(d));
        Instant s = A.minus(Duration.ofSeconds(125));
        Instant e = A.plus(Duration.ofSeconds(31));

        List<Instant> base = scenario.logs("checkout-api", "", s, e, 500).entries().stream().map(LogEntry::at).toList();
        List<Instant> moved = shifted.logs("checkout-api", "", s.plus(d), e.plus(d), 500).entries().stream()
                .map(LogEntry::at).toList();
        assertThat(moved).isEqualTo(base.stream().map(t -> t.plus(d)).toList());
        assertThat(base).isNotEmpty().allSatisfy(t -> assertThat(t).isBetween(s, e));
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThatThrownBy(() -> scenario.metric("checkout-api", "up", A, A.minusSeconds(1), Duration.ofSeconds(15), 300))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scenario.metric("checkout-api", "up", A, A, Duration.ZERO, 300))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scenario.logs("checkout-api", "", A, A, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static double valueAt(MetricSeries series, String at) {
        Instant t = Instant.parse(at);
        return series.points().stream().filter(p -> p.at().equals(t)).findFirst().map(MetricPoint::value)
                .orElseThrow(() -> new AssertionError("no point at " + at));
    }
}
