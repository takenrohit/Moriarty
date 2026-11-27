package dev.investigator.observability;

import dev.investigator.domain.InvestigationStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * The investigator's own meters from architecture §8 (Req 2, CP-70), exposed at
 * {@code /actuator/prometheus} and scraped by Prometheus. Tag values are bounded: statuses, tool and
 * model names, {@code input}/{@code output}, and the {@code validation_status} values below.
 *
 * <p>The constructor registers every known tag combination at zero. A series that first appears with
 * value 1 shows no increase to {@code rate()}, so without this the dashboards stayed at 0 for the first
 * investigation, tool call or report after a restart.
 */
@Component
public class InvestigatorMetrics {

    public static final String INVESTIGATION_DURATION = "investigator.investigation.duration";
    public static final String TOOL_CALLS = "investigator.tool.calls";
    public static final String TOOL_LATENCY = "investigator.tool.latency";
    public static final String LLM_TOKENS = "investigator.llm.tokens";
    public static final String REPORT_GENERATED = "investigator.report.generated";
    public static final String APPROVAL_WAIT = "investigator.approval.wait";

    /** {@code validation_status}: the first draft passed validation. */
    public static final String REPORT_VALID = "valid";
    /** {@code validation_status}: the draft passed after the one correction round. */
    public static final String REPORT_CORRECTED = "corrected";
    /** {@code validation_status}: the evidence-only fallback report was stored. */
    public static final String REPORT_FALLBACK = "fallback";

    public static final String INPUT = "input";
    public static final String OUTPUT = "output";

    /** The model's tools (the {@code @Tool} methods of {@code ObservabilityTools}), registered at zero. */
    public static final List<String> TOOLS =
            List.of("getMetric", "searchLogs", "findProblemTraces", "getRecentChanges", "findRunbook");
    /** Tool call outcomes, registered at zero. */
    public static final List<String> TOOL_STATUSES = List.of("success", "rejected", "error");

    private static final List<InvestigationStatus> TERMINAL_STATUSES =
            List.of(InvestigationStatus.COMPLETED, InvestigationStatus.INCONCLUSIVE, InvestigationStatus.FAILED);
    private static final String UNKNOWN = "unknown";

    private final MeterRegistry registry;

    public InvestigatorMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        TERMINAL_STATUSES.forEach(status -> investigationTimer(status.name()));
        for (String tool : TOOLS) {
            TOOL_STATUSES.forEach(status -> toolCalls(tool, status));
            toolLatency(tool);
        }
        List.of(REPORT_VALID, REPORT_CORRECTED, REPORT_FALLBACK).forEach(this::reports);
        approvalWaitTimer();
    }

    /** An investigation reached a terminal status (Req 2.1). */
    public void investigationFinished(InvestigationStatus status, Duration duration) {
        investigationTimer(status == null ? UNKNOWN : status.name()).record(duration);
    }

    /** A tool call finished with {@code status} {@code success}, {@code rejected} or {@code error} (Req 2.2). */
    public void toolCallFinished(String tool, String status, Duration duration) {
        String name = orUnknown(tool);
        toolCalls(name, orUnknown(status)).increment();
        toolLatency(name).record(duration);
    }

    /** Token usage of one LLM response; null counts are skipped (Req 2.3). */
    public void llmTokens(String model, Integer inputTokens, Integer outputTokens) {
        tokens(model, INPUT, inputTokens);
        tokens(model, OUTPUT, outputTokens);
    }

    /** A report was stored (Req 2.4). */
    public void reportGenerated(String validationStatus) {
        reports(orUnknown(validationStatus)).increment();
    }

    /** Time from an approval request to its decision (Req 2.5); called by the approval workflow. */
    public void approvalWait(Duration wait) {
        approvalWaitTimer().record(wait);
    }

    private Timer investigationTimer(String status) {
        return Timer.builder(INVESTIGATION_DURATION).description("Investigation run time by terminal status")
                .tag("status", status)
                .register(registry);
    }

    private Counter toolCalls(String tool, String status) {
        return Counter.builder(TOOL_CALLS).description("Tool calls by tool and outcome")
                .tag("tool", tool).tag("status", status)
                .register(registry);
    }

    private Timer toolLatency(String tool) {
        return Timer.builder(TOOL_LATENCY).description("Tool call latency by tool")
                .tag("tool", tool)
                .register(registry);
    }

    private Counter reports(String validationStatus) {
        return Counter.builder(REPORT_GENERATED).description("Stored reports by validation outcome")
                .tag("validation_status", validationStatus)
                .register(registry);
    }

    private Timer approvalWaitTimer() {
        return Timer.builder(APPROVAL_WAIT).description("Approval request-to-decision time")
                .register(registry);
    }

    private void tokens(String model, String direction, Integer count) {
        if (count == null || count < 0) {
            return;
        }
        Counter.builder(LLM_TOKENS).description("LLM tokens by direction and model")
                .tag("direction", direction).tag("model", orUnknown(model))
                .register(registry).increment(count);
    }

    private static String orUnknown(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value;
    }
}
