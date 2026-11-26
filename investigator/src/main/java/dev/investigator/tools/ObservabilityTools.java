package dev.investigator.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.investigator.config.InvestigatorProperties;
import dev.investigator.domain.EvidenceType;
import dev.investigator.evidence.ChangesSummary;
import dev.investigator.evidence.EvidenceService;
import dev.investigator.evidence.LogSummarizer;
import dev.investigator.evidence.MetricSummarizer;
import dev.investigator.evidence.MetricSummary;
import dev.investigator.evidence.ResultCapper;
import dev.investigator.evidence.RunbookSummary;
import dev.investigator.evidence.StoredEvidence;
import dev.investigator.evidence.TraceSummarizer;
import dev.investigator.integration.changes.ChangeClient;
import dev.investigator.integration.changes.ChangeRecord;
import dev.investigator.integration.http.IntegrationCallException;
import dev.investigator.integration.logs.LogClient;
import dev.investigator.integration.logs.LogEntry;
import dev.investigator.integration.logs.LogSearchResult;
import dev.investigator.integration.metrics.MetricSeries;
import dev.investigator.integration.metrics.MetricsClient;
import dev.investigator.integration.traces.TraceClient;
import dev.investigator.integration.traces.TraceRecord;
import dev.investigator.integration.traces.TraceSearchResult;
import dev.investigator.investigation.InvestigationContext;
import dev.investigator.investigation.InvestigationScope;
import dev.investigator.logging.LogFields;
import dev.investigator.runbook.RunbookCatalog;
import dev.investigator.runbook.RunbookMatch;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.stereotype.Component;

/**
 * The five read-only tools exposed to the Investigator_Agent (Req 10.1).
 *
 * <p>Every tool first calls {@link ToolGuard#begin}, which counts the invocation against the
 * Tool_Call_Budget and refuses it unless the Investigation is {@code RUNNING} and within budget
 * (Req 11.10 to 11.13). The tool then validates its arguments with the guard (allow-list, window
 * clamp, text and look-back limits, Req 11.1 to 11.9), calls one Integration_Client inside
 * {@link InvestigationScope#with}, stores the result through {@link EvidenceService} and returns the
 * capped JSON summary, which carries the Evidence_ID, the applied range and the {@code clamped} flag
 * (Req 9.1, 9.4, 11.3, 11.8). A refusal gives {@code {"error", "hint"}} and calls no client
 * (Req 10.9); a client exception gives an error result, is logged, and stores no Evidence
 * (Req 10.10).
 */
@Component
public class ObservabilityTools {

    static final String INTEGRATION_FAILED = "integration call failed";
    static final String INTEGRATION_HINT = "try a different query or tool";

    /** {@code redaction_status} logged when the call stored no Evidence (rejected, error, no runbook). */
    static final String REDACTION_NONE = "none";

    private static final Logger log = LoggerFactory.getLogger(ObservabilityTools.class);

    private final ToolGuard guard;
    private final EvidenceService evidence;
    private final MetricsClient metrics;
    private final LogClient logs;
    private final TraceClient traces;
    private final ChangeClient changes;
    private final RunbookCatalog runbooks;
    private final MetricSummarizer metricSummarizer;
    private final LogSummarizer logSummarizer;
    private final TraceSummarizer traceSummarizer;
    private final ObjectMapper mapper;
    private final InvestigatorProperties.Tools limits;
    private final Clock clock;

    public ObservabilityTools(ToolGuard guard, EvidenceService evidence,
            MetricsClient metrics, LogClient logs, TraceClient traces, ChangeClient changes,
            RunbookCatalog runbooks, MetricSummarizer metricSummarizer, LogSummarizer logSummarizer,
            TraceSummarizer traceSummarizer, ObjectMapper mapper, InvestigatorProperties properties,
            Clock clock) {
        this.guard = guard;
        this.evidence = evidence;
        this.metrics = metrics;
        this.logs = logs;
        this.traces = traces;
        this.changes = changes;
        this.runbooks = runbooks;
        this.metricSummarizer = metricSummarizer;
        this.logSummarizer = logSummarizer;
        this.traceSummarizer = traceSummarizer;
        this.mapper = mapper;
        this.limits = properties.tools();
        this.clock = clock;
    }

    @Tool("Query a metric time series for one service. start/end are ISO-8601 instants, e.g. 2026-11-23T14:00:00Z."
            + " The PromQL query must return a single series: aggregate with sum(...), e.g."
            + " sum(rate(http_server_requests_seconds_count{service=\"checkout-api\",status=~\"5..\"}[5m]))"
            + " / sum(rate(http_server_requests_seconds_count{service=\"checkout-api\"}[5m])).")
    public String getMetric(@ToolMemoryId String incidentId, @P("service") String service,
            @P("PromQL query") String promql, @P("start") String start, @P("end") String end) {
        return invoke("getMetric", incidentId, ctx -> {
            String svc = guard.requireAllowedService(service);
            String query = guard.requireQuery(promql);
            TimeRange range = guard.clampToWindow(ctx, start, end);
            Duration step = metricStep(range.span(), limits.minMetricStep(), limits.maxMetricPoints());
            MetricSeries series = InvestigationScope.with(ctx,
                    () -> metrics.queryRange(svc, query, range.start(), range.end(), step));
            MetricSummary probe = metricSummarizer.summarize(null, svc, query, range.start(), range.end(),
                    range.clamped(), series);
            Instant observedAt = probe.changePointAt() != null ? probe.changePointAt() : range.end();
            String citation = "PromQL " + query + " [" + range.start() + ", " + range.end() + "] step "
                    + series.step();
            StoredEvidence ev = evidence.store(ctx, EvidenceType.METRIC, "metrics", observedAt, citation,
                    id -> metricSummarizer.summarize(id, svc, query, range.start(), range.end(), range.clamped(),
                            series));
            return Outcome.stored(ev.evidence().summary(), List.of(ev));
        });
    }

    @Tool("Search logs for one service with a LogQL query. Returns counts and the top distinct messages.")
    public String searchLogs(@ToolMemoryId String incidentId, @P("service") String service,
            @P("LogQL query") String query, @P("start") String start, @P("end") String end) {
        return invoke("searchLogs", incidentId, ctx -> {
            String svc = guard.requireAllowedService(service);
            String logql = guard.requireQuery(query);
            TimeRange range = guard.clampToWindow(ctx, start, end);
            LogSearchResult result = InvestigationScope.with(ctx,
                    () -> logs.search(svc, logql, range.start(), range.end(), limits.logFetchLimit()));
            Instant observedAt = result.entries().stream().map(LogEntry::at).filter(Objects::nonNull)
                    .min(Instant::compareTo).orElse(range.end());
            String citation = "LogQL " + logql + " [" + range.start() + ", " + range.end() + "]";
            StoredEvidence ev = evidence.store(ctx, EvidenceType.LOG, "logs", observedAt, citation,
                    id -> logSummarizer.summarize(id, svc, logql, range.start(), range.end(), range.clamped(),
                            result));
            return Outcome.stored(ev.evidence().summary(), List.of(ev));
        });
    }

    @Tool("Find error and slow traces for one service. Returns failing spans and example trace IDs.")
    public String findProblemTraces(@ToolMemoryId String incidentId, @P("service") String service,
            @P("start") String start, @P("end") String end) {
        return invoke("findProblemTraces", incidentId, ctx -> {
            String svc = guard.requireAllowedService(service);
            TimeRange range = guard.clampToWindow(ctx, start, end);
            TraceSearchResult result = InvestigationScope.with(ctx, () -> traces.findProblemTraces(svc,
                    range.start(), range.end(), limits.slowTraceThreshold(), limits.traceLimit()));
            Instant observedAt = result.traces().stream().map(TraceRecord::start).filter(Objects::nonNull)
                    .min(Instant::compareTo).orElse(range.end());
            String citation = "traces service=" + svc + " error or duration >= " + limits.slowTraceThreshold()
                    + " [" + range.start() + ", " + range.end() + "]";
            StoredEvidence ev = evidence.store(ctx, EvidenceType.TRACE, "traces", observedAt, citation,
                    id -> traceSummarizer.summarize(id, svc, range.start(), range.end(), range.clamped(), result));
            return Outcome.stored(ev.evidence().summary(), List.of(ev));
        });
    }

    @Tool("List deployments and changes for one service in the last lookbackHours (max 24).")
    public String getRecentChanges(@ToolMemoryId String incidentId, @P("service") String service,
            @P("lookbackHours") int lookbackHours) {
        return invoke("getRecentChanges", incidentId, ctx -> {
            String svc = guard.requireAllowedService(service);
            Lookback lookback = guard.lookback(ctx, lookbackHours);
            List<ChangeRecord> records = InvestigationScope.with(ctx,
                    () -> changes.recentChanges(svc, lookback.since(), lookback.until()));
            List<StoredEvidence> storedEvidence = new ArrayList<>();
            List<ChangesSummary.Change> stored = new ArrayList<>();
            for (ChangeRecord record : records) {
                String citation = record.link() != null && !record.link().isBlank()
                        ? record.link()
                        : "changes service=" + record.service() + " version=" + record.version();
                StoredEvidence ev = evidence.store(ctx, EvidenceType.DEPLOYMENT, "changes", record.deployedAt(),
                        citation, id -> ChangesSummary.Change.from(id, record, limits.maxChangedFiles()));
                storedEvidence.add(ev);
                // Rebuilt from the redacted payload, so the model sees what the Redactor let through.
                stored.add(mapper.convertValue(ev.evidence().payload(), ChangesSummary.Change.class));
            }
            ChangesSummary summary = new ChangesSummary(svc, lookback.hours(), lookback.since(), lookback.until(),
                    lookback.clamped(), stored);
            String firstId = storedEvidence.isEmpty() ? null : storedEvidence.get(0).evidence().id();
            String result = ResultCapper.cap(mapper, summary, firstId, limits.maxResultBytes());
            return Outcome.stored(result, storedEvidence);
        });
    }

    @Tool("Find the most relevant runbook for a service and symptom.")
    public String findRunbook(@ToolMemoryId String incidentId, @P("service") String service,
            @P("symptom") String symptom) {
        return invoke("findRunbook", incidentId, ctx -> {
            String svc = guard.requireAllowedService(service);
            String text = guard.requireSymptom(symptom);
            Optional<RunbookMatch> match = runbooks.bestMatch(svc, text);
            if (match.isEmpty()) {
                return Outcome.noEvidence(ToolResults.NOT_FOUND);
            }
            RunbookMatch runbook = match.get();
            StoredEvidence ev = evidence.store(ctx, EvidenceType.RUNBOOK, "runbooks", clock.instant(),
                    "runbooks/" + runbook.fileName(),
                    id -> RunbookSummary.of(id, runbook.title(), runbook.service(), runbook.steps()));
            return Outcome.stored(ev.evidence().summary(), List.of(ev));
        });
    }

    /**
     * Metric query step (design "Tool guard"): {@code max(minStep, ceilToSeconds(span / (maxPoints − 1)))},
     * so a range of {@code span} yields at most {@code floor(span/step) + 1 ≤ maxPoints} points (Req 11.14).
     */
    public static Duration metricStep(Duration span, Duration minStep, int maxPoints) {
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(minStep, "minStep");
        if (span.isNegative()) {
            throw new IllegalArgumentException("span must not be negative");
        }
        if (maxPoints < 2) {
            throw new IllegalArgumentException("maxPoints must be at least 2");
        }
        // ceil(ceil(x) / k) == ceil(x / k) for integer k, so rounding the span up first is exact.
        long spanSeconds = span.getSeconds() + (span.getNano() > 0 ? 1 : 0);
        Duration step = Duration.ofSeconds(Math.ceilDiv(spanSeconds, (long) maxPoints - 1));
        return step.compareTo(minStep) >= 0 ? step : minStep;
    }

    /** Shared wrapper: guard first (counts every invocation), then the body, then one log line. */
    private String invoke(String toolName, String incidentId, Function<InvestigationContext, Outcome> body) {
        long started = System.nanoTime();
        Outcome outcome;
        try {
            InvestigationContext ctx = guard.begin(incidentId, toolName);
            outcome = body.apply(ctx);
        } catch (ToolRejectedException e) {
            outcome = Outcome.rejected(e.toResult());
        } catch (RuntimeException e) {
            log.atWarn().addKeyValue(LogFields.TOOL_NAME, toolName).setCause(e).log("tool call failed");
            // A client hint is a constant text (never the query or backend output), so it is safe to return.
            String hint = e instanceof IntegrationCallException call && call.hint() != null
                    ? call.hint()
                    : INTEGRATION_HINT;
            outcome = Outcome.failed(ToolResults.error(INTEGRATION_FAILED, hint));
        }
        long durationMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        logFinished(toolName, outcome, durationMs);
        return outcome.result;
    }

    /**
     * The one structured entry per tool call (Req 18.3). It carries names, IDs, the outcome and the
     * timing only; arguments, results and payloads are never logged (Req 18.5). {@code incident_id}
     * and {@code trace_id} come from the MDC of the agent thread.
     */
    private static void logFinished(String toolName, Outcome outcome, long durationMs) {
        List<String> ids = outcome.stored.stream().map(s -> s.evidence().id()).toList();
        String redactionStatus = outcome.stored.isEmpty()
                ? REDACTION_NONE
                : String.join(",", outcome.stored.stream().map(StoredEvidence::redactionStatus).distinct().toList());
        LoggingEventBuilder entry = log.atInfo()
                .addKeyValue(LogFields.TOOL_NAME, toolName)
                .addKeyValue(LogFields.OUTCOME, outcome.name)
                .addKeyValue(LogFields.DURATION_MS, durationMs)
                .addKeyValue(LogFields.REDACTION_STATUS, redactionStatus);
        if (!ids.isEmpty()) {
            entry = entry.addKeyValue(LogFields.EVIDENCE_ID, String.join(",", ids));
        }
        entry.log("tool call finished");
    }

    /** Result JSON of one invocation plus what the log entry reports. */
    private record Outcome(String name, String result, List<StoredEvidence> stored) {

        static Outcome stored(String result, List<StoredEvidence> stored) {
            return new Outcome("success", result, List.copyOf(stored));
        }

        static Outcome noEvidence(String result) {
            return new Outcome("success", result, List.of());
        }

        static Outcome rejected(String result) {
            return new Outcome("rejected", result, List.of());
        }

        static Outcome failed(String result) {
            return new Outcome("error", result, List.of());
        }
    }
}
