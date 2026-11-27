package dev.investigator.observability;

import static org.assertj.core.api.Assertions.assertThat;

import dev.investigator.tools.ObservabilityTools;
import dev.langchain4j.agent.tool.Tool;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class InvestigatorMetricsTest {

    @Test
    void knownSeriesAreRegisteredAtZeroSoTheFirstIncrementShowsInRate() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new InvestigatorMetrics(registry);

        assertThat(registry.get(InvestigatorMetrics.INVESTIGATION_DURATION).tag("status", "COMPLETED").timer().count())
                .isZero();
        assertThat(registry.get(InvestigatorMetrics.INVESTIGATION_DURATION).timers()).hasSize(3);
        assertThat(registry.get(InvestigatorMetrics.TOOL_CALLS).tags("tool", "searchLogs", "status", "error")
                .counter().count()).isZero();
        assertThat(registry.get(InvestigatorMetrics.TOOL_CALLS).counters()).hasSize(15);
        assertThat(registry.get(InvestigatorMetrics.TOOL_LATENCY).timers()).hasSize(5);
        assertThat(registry.get(InvestigatorMetrics.REPORT_GENERATED).counters()).hasSize(3);
        assertThat(registry.get(InvestigatorMetrics.APPROVAL_WAIT).timer().count()).isZero();
    }

    @Test
    void toolListMatchesTheToolMethods() {
        assertThat(Arrays.stream(ObservabilityTools.class.getMethods())
                .filter(m -> m.isAnnotationPresent(Tool.class))
                .map(Method::getName))
                .containsExactlyInAnyOrderElementsOf(InvestigatorMetrics.TOOLS);
    }
}
