package dev.investigator.integration.mock;

import static org.assertj.core.api.Assertions.assertThat;

import dev.investigator.integration.changes.ChangeClient;
import dev.investigator.integration.changes.ChangeRecord;
import dev.investigator.integration.logs.LogClient;
import dev.investigator.integration.metrics.MetricsClient;
import dev.investigator.integration.traces.TraceClient;
import dev.investigator.investigation.InvestigationContext;
import dev.investigator.investigation.InvestigationScope;
import dev.investigator.testsupport.InvestigatorSpringBootTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** With no profile set, the mock clients are wired and read the anchor from the investigation scope. */
@InvestigatorSpringBootTest
class MockClientsWiringTest {

    private static final Instant DEMO_ANCHOR = Instant.parse("2026-11-23T14:40:00Z");

    @Autowired
    MetricsClient metrics;

    @Autowired
    LogClient logs;

    @Autowired
    TraceClient traces;

    @Autowired
    ChangeClient changes;

    @Test
    void mockClientsAreActiveByDefault() {
        assertThat(metrics).isInstanceOf(MockMetricsClient.class);
        assertThat(logs).isInstanceOf(MockLogClient.class);
        assertThat(traces).isInstanceOf(MockTraceClient.class);
        assertThat(changes).isInstanceOf(MockChangeClient.class);
    }

    @Test
    void anchorComesFromScopeOrDefault() {
        Instant other = Instant.parse("2027-01-05T03:00:00Z");
        InvestigationContext ctx = new InvestigationContext("inc-0123456789ab", other.minus(Duration.ofHours(1)),
                other.plus(Duration.ofMinutes(10)), other.plus(Duration.ofMinutes(3)), "0".repeat(32),
                "ev-inc-0123456789ab-1", other, 15);

        List<ChangeRecord> scoped = InvestigationScope.with(ctx,
                () -> changes.recentChanges("checkout-api", other.minus(Duration.ofHours(24)), other));
        List<ChangeRecord> unscoped = changes.recentChanges("checkout-api",
                DEMO_ANCHOR.minus(Duration.ofHours(24)), DEMO_ANCHOR);

        assertThat(scoped).extracting(ChangeRecord::deployedAt).containsExactly(other.minus(Duration.ofMinutes(14)));
        assertThat(unscoped).extracting(ChangeRecord::deployedAt)
                .containsExactly(Instant.parse("2026-11-23T14:26:00Z"));
        assertThat(InvestigationScope.current()).isEmpty();
    }

    @Test
    void clientsCapLogsTracesAndPoints() {
        Instant logStart = DEMO_ANCHOR.minus(Duration.ofMinutes(70));
        var logResult = logs.search("checkout-api", "", logStart, logStart.plus(Duration.ofHours(6)), 10_000);
        assertThat(logResult.entries()).hasSize(500);
        assertThat(logResult.truncated()).isTrue();

        Instant start = DEMO_ANCHOR.minus(Duration.ofHours(6));
        assertThat(traces.findProblemTraces("checkout-api", start, DEMO_ANCHOR.plus(Duration.ofMinutes(10)),
                Duration.ofSeconds(2), 1_000).traces()).hasSize(50);
        assertThat(metrics.queryRange("checkout-api", "up", start, DEMO_ANCHOR, Duration.ofSeconds(1)).points())
                .hasSizeLessThanOrEqualTo(300);
    }
}
