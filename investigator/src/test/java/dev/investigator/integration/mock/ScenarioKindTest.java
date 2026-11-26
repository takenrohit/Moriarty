package dev.investigator.integration.mock;

import static org.assertj.core.api.Assertions.assertThat;

import dev.investigator.integration.changes.ChangeRecord;
import dev.investigator.integration.metrics.MetricSeries;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Scenario_Library selection and the distinguishing shape of each scenario (reliability Req 10.1 to 10.3). */
class ScenarioKindTest {

    private static final Instant A = Instant.parse("2026-11-23T14:40:00Z");
    private static final Instant WS = A.minus(Duration.ofMinutes(60));
    private static final Instant WE = A.plus(Duration.ofMinutes(10));

    @Test
    void labelSelectsTheScenarioCaseInsensitivelyAndDefaultsOtherwise() {
        assertThat(ScenarioKind.fromLabel("bad-deploy")).isEqualTo(ScenarioKind.BAD_DEPLOY);
        assertThat(ScenarioKind.fromLabel(" BAD_DEPLOY ")).isEqualTo(ScenarioKind.BAD_DEPLOY);
        assertThat(ScenarioKind.fromLabel("Db-Pool-Exhaustion")).isEqualTo(ScenarioKind.DB_POOL_EXHAUSTION);
        assertThat(ScenarioKind.fromLabel("noisy-neighbor")).isEqualTo(ScenarioKind.NOISY_NEIGHBOUR);
        assertThat(ScenarioKind.fromLabel("noisy neighbour")).isEqualTo(ScenarioKind.NOISY_NEIGHBOUR);
        assertThat(ScenarioKind.fromLabel("payment-gateway")).isEqualTo(ScenarioKind.PAYMENT_GATEWAY);
        assertThat(ScenarioKind.fromLabel(null)).isEqualTo(ScenarioKind.PAYMENT_GATEWAY);
        assertThat(ScenarioKind.fromLabel("")).isEqualTo(ScenarioKind.PAYMENT_GATEWAY);
        assertThat(ScenarioKind.fromLabel("meteor-strike")).isEqualTo(ScenarioKind.PAYMENT_GATEWAY);
        assertThat(ScenarioKind.BAD_DEPLOY.expectedCause().deployIsCause()).isTrue();
        assertThat(ScenarioKind.NOISY_NEIGHBOUR.expectedCause().deployIsCause()).isFalse();
    }

    @Test
    void defaultForAnchorIsThePaymentGatewayScenario() {
        assertThat(MockScenario.forAnchor(A).kind()).isEqualTo(ScenarioKind.PAYMENT_GATEWAY);
        MockScenario explicit = MockScenario.forAnchor(ScenarioKind.PAYMENT_GATEWAY, A);
        assertThat(explicit.logs("checkout-api", "", WS, WE, 500))
                .isEqualTo(MockScenario.forAnchor(A).logs("checkout-api", "", WS, WE, 500));
    }

    @Test
    void badDeployChangesThePaymentClientAndErrorsFollowIt() {
        MockScenario s = MockScenario.forAnchor(ScenarioKind.BAD_DEPLOY, A);

        ChangeRecord deploy = s.changes("checkout-api", WE.minus(Duration.ofHours(24)), WE).get(0);
        assertThat(deploy.version()).isEqualTo("v2.14.3");
        assertThat(deploy.changedFiles()).anyMatch(f -> f.endsWith("PaymentClient.java"))
                .anyMatch(f -> f.endsWith("RetryPolicy.java"));
        MetricSeries errors = s.metric("checkout-api", "sum(rate(x{status=~\"5..\"}[5m]))", WS, WE,
                Duration.ofMinutes(1), 300);
        assertThat(value(errors, A.minus(Duration.ofMinutes(13)))).isEqualTo(0.002);
        assertThat(value(errors, A.minus(Duration.ofMinutes(12)))).isEqualTo(0.12);
        assertThat(s.logs("checkout-api", "|= \"NullPointerException\"", WS, WE, 500).entries().get(0).at())
                .isEqualTo(A.minus(Duration.ofMinutes(12)));
        assertThat(s.traces("payment-gateway", WS, WE, Duration.ofSeconds(2), 50).traces()).isEmpty();
        assertThat(s.metric("payment-gateway", "histogram_quantile(0.99, x)", WS, WE, Duration.ofMinutes(1), 300)
                .points()).allSatisfy(p -> assertThat(p.value()).isEqualTo(180.0));
    }

    @Test
    void dbPoolAndNoisyNeighbourHaveNoDeployInTheLookback() {
        for (ScenarioKind kind : new ScenarioKind[] {ScenarioKind.DB_POOL_EXHAUSTION, ScenarioKind.NOISY_NEIGHBOUR}) {
            MockScenario s = MockScenario.forAnchor(kind, A);
            assertThat(s.changes("checkout-api", WE.minus(Duration.ofHours(24)), WE)).as("%s", kind).isEmpty();
        }
        MockScenario db = MockScenario.forAnchor(ScenarioKind.DB_POOL_EXHAUSTION, A);
        assertThat(db.logs("checkout-api", "|= \"HikariPool-1\"", WS, WE, 500).entries()).hasSize(150);
        assertThat(db.traces("checkout-api", WS, WE, Duration.ofSeconds(2), 50).traces().get(0).spans())
                .anyMatch(sp -> "postgres".equals(sp.peerService()) && sp.error());

        MockScenario noisy = MockScenario.forAnchor(ScenarioKind.NOISY_NEIGHBOUR, A);
        MetricSeries cpu = noisy.metric("checkout-api",
                "sum(rate(container_cpu_cfs_throttled_seconds_total{service=\"checkout-api\"}[5m]))", WS, WE,
                Duration.ofMinutes(1), 300);
        assertThat(value(cpu, A.minus(Duration.ofMinutes(7)))).isEqualTo(0.15);
        assertThat(value(cpu, A.minus(Duration.ofMinutes(6)))).isEqualTo(0.97);
        assertThat(noisy.traces("checkout-api", WS, WE, Duration.ofSeconds(2), 50).traces())
                .isNotEmpty().allSatisfy(t -> assertThat(t.error()).isFalse());
        assertThat(noisy.logs("checkout-api", "|= \"GC overhead\"", WS, WE, 500).entries()).isNotEmpty();
    }

    private static double value(MetricSeries series, Instant at) {
        return series.points().stream().filter(p -> p.at().equals(at)).findFirst().orElseThrow().value();
    }
}
