package dev.investigator.investigation;

import dev.investigator.config.InvestigatorProperties;
import dev.investigator.domain.Evidence;
import dev.investigator.domain.Incident;
import dev.investigator.domain.IncidentStatus;
import dev.investigator.domain.IncidentTimeline;
import dev.investigator.domain.Investigation;
import dev.investigator.domain.InvestigationReport;
import dev.investigator.domain.InvestigationStatus;
import dev.investigator.evidence.EvidenceService;
import dev.investigator.report.FallbackReportBuilder;
import dev.investigator.report.ParseResult;
import dev.investigator.report.ReportAssembler;
import dev.investigator.report.ReportDraftParser;
import dev.investigator.report.ReportPublisher;
import dev.investigator.store.EvidenceRepository;
import dev.investigator.store.IncidentRepository;
import dev.investigator.store.InvestigationRepository;
import dev.investigator.store.ReportRepository;
import jakarta.annotation.PreDestroy;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Investigation_Orchestrator: runs one investigation per Incident asynchronously and always ends it
 * with exactly one report (Req 14).
 *
 * <p>{@link #start} runs on the request thread: it creates the {@code PENDING} Investigation with its
 * window, stores the alert as {@code ALERT} Evidence, registers the {@link InvestigationContext} with
 * the deadline {@code creation + investigator.budget.timeout} and a 32-hex trace ID, and submits
 * {@link #run} to a virtual-thread executor. {@link #run} moves the Investigation to {@code RUNNING},
 * calls the {@link InvestigatorAgent} on a second virtual thread and waits until the deadline. The
 * result maps to a terminal status as in the design's outcome table:
 *
 * <ul>
 *   <li>valid draft, budget not exhausted: report from the draft, {@code COMPLETED};
 *   <li>valid draft, budget exhausted: report from the draft plus a budget open question,
 *       {@code INCONCLUSIVE};
 *   <li>draft fails parsing: fallback report listing the errors, {@code INCONCLUSIVE};
 *   <li>timeout: fallback report, {@code INCONCLUSIVE}; the agent thread is interrupted;
 *   <li>tool-calling round-trip limit: fallback report, {@code INCONCLUSIVE};
 *   <li>any other exception: fallback report, {@code FAILED}.
 * </ul>
 *
 * <p>The terminal status is set with a compare-and-set {@link InvestigationRepository#transition}; only
 * the caller that wins it stores the report ({@link ReportRepository#saveIfAbsent}), sets the
 * Incident to {@code REPORTED}, writes the report to the log ({@link ReportPublisher}), evicts the chat
 * memory and removes the context. An agent that returns
 * after the investigation ended has its result discarded with a warning (Req 14.10).
 */
@Component
public class InvestigationOrchestrator {

    static final String MDC_INCIDENT_ID = "incident_id";
    static final String MDC_TRACE_ID = "trace_id";

    static final String DRAFT_INVALID = "the report draft failed validation";
    static final String ROUND_TRIP_LIMIT = "tool-calling limit reached";
    static final String LLM_FAILED = "LLM call failed: ";
    static final String TIMED_OUT = "timed out after ";
    static final String INTERRUPTED = "investigation was interrupted";
    static final String INTERNAL_ERROR = "internal error: ";

    /** Marker in the message of the exception LangChain4j 1.24.0 throws when the round-trip limit is hit. */
    static final String ROUND_TRIP_MARKER = "maxToolCallingRoundTrips";

    private static final Logger log = LoggerFactory.getLogger(InvestigationOrchestrator.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private final InvestigationRepository investigations;
    private final IncidentRepository incidents;
    private final ReportRepository reports;
    private final EvidenceRepository evidenceRepository;
    private final EvidenceService evidenceService;
    private final InvestigationContextRegistry contexts;
    private final InvestigatorAgent agent;
    private final PromptInputs promptInputs;
    private final ReportDraftParser parser;
    private final ReportAssembler assembler;
    private final FallbackReportBuilder fallback;
    private final ReportPublisher publisher;
    private final InvestigatorProperties properties;
    private final Clock clock;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public InvestigationOrchestrator(InvestigationRepository investigations, IncidentRepository incidents,
            ReportRepository reports, EvidenceRepository evidenceRepository, EvidenceService evidenceService,
            InvestigationContextRegistry contexts, InvestigatorAgent agent, PromptInputs promptInputs,
            ReportDraftParser parser, ReportAssembler assembler, FallbackReportBuilder fallback,
            ReportPublisher publisher, InvestigatorProperties properties, Clock clock) {
        this.investigations = investigations;
        this.incidents = incidents;
        this.reports = reports;
        this.evidenceRepository = evidenceRepository;
        this.evidenceService = evidenceService;
        this.contexts = contexts;
        this.agent = agent;
        this.promptInputs = promptInputs;
        this.parser = parser;
        this.assembler = assembler;
        this.fallback = fallback;
        this.publisher = publisher;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Creates the {@code PENDING} Investigation for a stored Incident and starts it asynchronously
     * (Req 4.4, 6.2, 7.1, 9.7, 14.1, 14.2). Returns without waiting for the agent.
     *
     * <p>If setup after creation fails (for example the executor rejects the task), the Investigation
     * moves {@code PENDING→FAILED} with a fallback report, and the returned Investigation is
     * {@code FAILED}.
     *
     * @throws IllegalStateException if the Incident already has an Investigation
     */
    public Investigation start(Incident incident) {
        Objects.requireNonNull(incident, "incident");
        String incidentId = incident.id();
        InvestigationWindowCalculator.Window window =
                InvestigationWindowCalculator.window(incident.detectedAt(), properties.window());
        Instant createdAt = clock.instant();
        Investigation investigation = new Investigation(incidentId, InvestigationStatus.PENDING, window.start(),
                window.end(), 0, List.of(), List.of(), new IncidentTimeline(List.of()), List.of());
        investigations.create(investigation);

        try {
            String traceId = newTraceId();
            Evidence alert = evidenceService.storeAlert(incident, traceId);
            Instant deadline = createdAt.plus(properties.budget().timeout());
            InvestigationContext ctx = new InvestigationContext(incidentId, window.start(), window.end(), deadline,
                    traceId, alert.id(), incident.detectedAt(), properties.budget().maxToolCalls());
            contexts.register(ctx);
            executor.submit(() -> run(incidentId));
            log.info("investigation created incident_id={} trace_id={} window_start={} window_end={} deadline={}",
                    incidentId, traceId, window.start(), window.end(), deadline);
            return investigation;
        } catch (RuntimeException e) {
            log.error("investigation could not be started incident_id={}", incidentId, e);
            failBeforeRun(incident, INTERNAL_ERROR + e.getClass().getSimpleName());
            return investigations.find(incidentId).orElse(investigation);
        }
    }

    /**
     * The asynchronous part of an investigation; runs on a virtual thread submitted by {@link #start}.
     * Does nothing if the Investigation has no context or is no longer {@code PENDING}.
     */
    public void run(String incidentId) {
        InvestigationContext ctx = contexts.find(incidentId).orElse(null);
        if (ctx == null) {
            log.warn("no investigation context; nothing to run incident_id={}", incidentId);
            return;
        }
        MDC.put(MDC_INCIDENT_ID, incidentId);
        MDC.put(MDC_TRACE_ID, ctx.traceId());
        try {
            if (!investigations.transition(incidentId, InvestigationStatus.PENDING, InvestigationStatus.RUNNING)) {
                log.warn("investigation is not PENDING; not running it incident_id={}", incidentId);
                return;
            }
            AtomicBoolean concluded = new AtomicBoolean();
            Outcome outcome;
            try {
                ctx.accepting().set(true);
                incidents.updateStatus(incidentId, IncidentStatus.INVESTIGATING);
                log.info("investigation running incident_id={}", incidentId);
                outcome = investigate(ctx, concluded);
            } catch (RuntimeException e) {
                log.error("investigation failed unexpectedly incident_id={}", incidentId, e);
                stopAccepting(ctx, concluded);
                outcome = fallbackOutcome(ctx.incidentId(), InvestigationStatus.FAILED,
                        INTERNAL_ERROR + e.getClass().getSimpleName(), List.of());
            }
            conclude(ctx, InvestigationStatus.RUNNING, outcome, concluded);
        } finally {
            MDC.remove(MDC_INCIDENT_ID);
            MDC.remove(MDC_TRACE_ID);
        }
    }

    /** Stops the executor; agent threads still running are interrupted. */
    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private Outcome investigate(InvestigationContext ctx, AtomicBoolean concluded) {
        String incidentId = ctx.incidentId();
        Incident incident = incidents.find(incidentId)
                .orElseThrow(() -> new IllegalStateException("no incident " + incidentId));
        String incidentJson = promptInputs.incidentJson(incident);
        Future<String> future = executor.submit(agentCall(ctx, incidentJson, concluded));

        AgentResult result;
        try {
            result = new AgentResult.Answer(future.get(remainingNanos(ctx), TimeUnit.NANOSECONDS));
        } catch (TimeoutException e) {
            future.cancel(true);
            result = new AgentResult.TimedOut();
        } catch (ExecutionException e) {
            result = new AgentResult.Failed(e.getCause() != null ? e.getCause() : e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            result = new AgentResult.Interrupted();
        }
        stopAccepting(ctx, concluded);
        return outcomeFor(ctx, result);
    }

    private Callable<String> agentCall(InvestigationContext ctx, String incidentJson, AtomicBoolean concluded) {
        String incidentId = ctx.incidentId();
        return () -> {
            MDC.put(MDC_INCIDENT_ID, incidentId);
            MDC.put(MDC_TRACE_ID, ctx.traceId());
            try {
                return agent.investigate(incidentId, incidentJson, PromptInputs.isoInstant(ctx.windowStart()),
                        PromptInputs.isoInstant(ctx.windowEnd()), ctx.alertEvidenceId(), promptInputs.reportSchema());
            } finally {
                if (concluded.get()) {
                    // The orchestrator already ended the investigation: drop the result and the memory
                    // this late call may have recreated (Req 14.10).
                    log.warn("agent returned after the investigation ended; result discarded incident_id={}",
                            incidentId);
                    agent.evictChatMemory(incidentId);
                }
                MDC.remove(MDC_INCIDENT_ID);
                MDC.remove(MDC_TRACE_ID);
            }
        };
    }

    private Outcome outcomeFor(InvestigationContext ctx, AgentResult result) {
        String incidentId = ctx.incidentId();
        return switch (result) {
            case AgentResult.Answer answer -> fromDraft(ctx, answer.raw());
            case AgentResult.TimedOut timedOut -> {
                String reason = TIMED_OUT + humanDuration(properties.budget().timeout());
                log.warn("investigation timed out incident_id={} reason=\"{}\"", incidentId, reason);
                yield fallbackOutcome(incidentId, InvestigationStatus.INCONCLUSIVE, reason, List.of());
            }
            case AgentResult.Failed failed when isRoundTripLimit(failed.cause()) -> {
                log.warn("agent hit the tool-calling round-trip limit incident_id={}", incidentId);
                yield fallbackOutcome(incidentId, InvestigationStatus.INCONCLUSIVE, ROUND_TRIP_LIMIT, List.of());
            }
            case AgentResult.Failed failed -> {
                log.error("agent call failed incident_id={}", incidentId, failed.cause());
                yield fallbackOutcome(incidentId, InvestigationStatus.FAILED,
                        LLM_FAILED + failed.cause().getClass().getSimpleName(), List.of());
            }
            case AgentResult.Interrupted interrupted -> {
                log.warn("investigation interrupted incident_id={}", incidentId);
                yield fallbackOutcome(incidentId, InvestigationStatus.FAILED, INTERRUPTED, List.of());
            }
        };
    }

    private Outcome fromDraft(InvestigationContext ctx, String raw) {
        String incidentId = ctx.incidentId();
        List<String> errors;
        switch (parser.parse(raw)) {
            case ParseResult.Ok ok -> {
                boolean exhausted = ctx.budgetExhausted().get();
                InvestigationStatus status = exhausted ? InvestigationStatus.INCONCLUSIVE : InvestigationStatus.COMPLETED;
                List<String> extra = exhausted ? List.of(budgetQuestion(ctx.budget())) : List.of();
                try {
                    InvestigationReport report = assembler.assemble(ok.draft(), ctx, status, extra,
                            evidenceService.refs(incidentId), clock.instant());
                    return new Outcome(status, report);
                } catch (IllegalArgumentException e) {
                    errors = List.of(e.getMessage());
                }
            }
            case ParseResult.Invalid invalid -> errors = invalid.errors();
        }
        log.warn("report draft rejected incident_id={} error_count={}", incidentId, errors.size());
        return fallbackOutcome(incidentId, InvestigationStatus.INCONCLUSIVE, DRAFT_INVALID, errors);
    }

    /** Open question added when the Tool_Call_Budget was reached (Req 14.4). */
    static String budgetQuestion(int budget) {
        return "Tool-call budget of " + budget + " calls was reached; the investigation may be incomplete.";
    }

    private Outcome fallbackOutcome(String incidentId, InvestigationStatus status, String reason, List<String> details) {
        String service = incidents.find(incidentId).map(Incident::affectedService).orElse(null);
        InvestigationReport report = fallback.build(incidentId, service, status, reason, details,
                evidenceRepository.findByIncident(incidentId), clock.instant());
        return new Outcome(status, report);
    }

    /** Writes the result if this caller wins the terminal transition (Req 14.9, 14.10). */
    private void conclude(InvestigationContext ctx, InvestigationStatus from, Outcome outcome,
            AtomicBoolean concluded) {
        String incidentId = ctx.incidentId();
        stopAccepting(ctx, concluded);
        investigations.recordToolCalls(incidentId, ctx.used().get());
        if (!investigations.transition(incidentId, from, outcome.status())) {
            log.warn("investigation already ended; late result discarded incident_id={} status={}",
                    incidentId, outcome.status());
            return;
        }
        boolean stored = reports.saveIfAbsent(outcome.report());
        if (!stored) {
            log.warn("a report is already stored; keeping it incident_id={}", incidentId);
        }
        incidents.updateStatus(incidentId, IncidentStatus.REPORTED);
        if (stored) {
            publish(incidentId, outcome.report());
        }
        agent.evictChatMemory(incidentId);
        contexts.remove(incidentId);
        log.info("investigation finished incident_id={} status={} tool_calls_used={}",
                incidentId, outcome.status(), ctx.used().get());
    }

    /** Ends an Investigation that never ran: {@code PENDING→FAILED} with a fallback report. */
    private void failBeforeRun(Incident incident, String reason) {
        String incidentId = incident.id();
        contexts.find(incidentId).ifPresent(ctx -> ctx.accepting().set(false));
        try {
            if (!investigations.transition(incidentId, InvestigationStatus.PENDING, InvestigationStatus.FAILED)) {
                log.warn("investigation already left PENDING; not failing it incident_id={}", incidentId);
                return;
            }
            InvestigationReport report = fallback.build(incidentId, incident.affectedService(),
                    InvestigationStatus.FAILED, reason, List.of(), evidenceRepository.findByIncident(incidentId),
                    clock.instant());
            boolean stored = reports.saveIfAbsent(report);
            incidents.updateStatus(incidentId, IncidentStatus.REPORTED);
            if (stored) {
                publish(incidentId, report);
            }
        } finally {
            contexts.remove(incidentId);
        }
    }

    /** Writes a newly stored report to the log (Req 17.9); the publisher never throws. */
    private void publish(String incidentId, InvestigationReport report) {
        incidents.find(incidentId).ifPresent(incident -> publisher.publish(incident, report));
    }

    private static void stopAccepting(InvestigationContext ctx, AtomicBoolean concluded) {
        ctx.accepting().set(false);
        concluded.set(true);
    }

    private long remainingNanos(InvestigationContext ctx) {
        long nanos = Duration.between(clock.instant(), ctx.deadline()).toNanos();
        return Math.max(0, nanos);
    }

    /**
     * True if {@code error} or one of its causes is LangChain4j's round-trip limit failure. LangChain4j
     * 1.24.0 throws a plain {@link RuntimeException} with the message
     * {@code "Something is wrong, exceeded N tool calling round trips (maxToolCallingRoundTrips)"}
     * ({@code ToolService}, via {@code Exceptions.runtime}), so the message is the only distinguishing
     * mark.
     */
    static boolean isRoundTripLimit(Throwable error) {
        int depth = 0;
        for (Throwable t = error; t != null && depth < 10; t = t.getCause(), depth++) {
            String message = t.getMessage();
            if (t instanceof RuntimeException && message != null && message.contains(ROUND_TRIP_MARKER)) {
                return true;
            }
        }
        return false;
    }

    /** {@code 3m}, {@code 45s} or {@code 300ms}. */
    static String humanDuration(Duration duration) {
        long millis = duration.toMillis();
        if (millis > 0 && millis % 60_000 == 0) {
            return (millis / 60_000) + "m";
        }
        if (millis > 0 && millis % 1_000 == 0) {
            return (millis / 1_000) + "s";
        }
        return millis + "ms";
    }

    /** 32 lowercase hex digits, never all zero (an all-zero trace ID is invalid in W3C Trace Context). */
    static String newTraceId() {
        byte[] bytes = new byte[16];
        String id;
        do {
            RANDOM.nextBytes(bytes);
            id = HEX.formatHex(bytes);
        } while (id.chars().allMatch(c -> c == '0'));
        return id;
    }

    /** Terminal status and the report to store with it. */
    private record Outcome(InvestigationStatus status, InvestigationReport report) {
    }

    /** What waiting for the agent produced. */
    private sealed interface AgentResult {

        record Answer(String raw) implements AgentResult {
        }

        record TimedOut() implements AgentResult {
        }

        record Failed(Throwable cause) implements AgentResult {
        }

        record Interrupted() implements AgentResult {
        }
    }
}
