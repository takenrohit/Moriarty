package dev.investigator.investigation;

import dev.investigator.audit.AuditService;
import dev.investigator.config.InvestigatorProperties;
import dev.investigator.domain.Evidence;
import dev.investigator.domain.EvidenceRef;
import dev.investigator.domain.Incident;
import dev.investigator.domain.IncidentStatus;
import dev.investigator.domain.IncidentTimeline;
import dev.investigator.domain.Investigation;
import dev.investigator.domain.InvestigationReport;
import dev.investigator.domain.InvestigationStatus;
import dev.investigator.evidence.EvidenceService;
import dev.investigator.logging.LogFields;
import dev.investigator.observability.InvestigatorMetrics;
import dev.investigator.report.FallbackReportBuilder;
import dev.investigator.report.ParseResult;
import dev.investigator.report.ReportAssembler;
import dev.investigator.report.ReportDraftParser;
import dev.investigator.report.ReportPublisher;
import dev.investigator.report.ReportValidator;
import dev.investigator.report.ValidationResult;
import dev.investigator.store.EvidenceRepository;
import dev.investigator.store.IncidentRepository;
import dev.investigator.store.InvestigationRepository;
import dev.investigator.store.ReportRepository;
import io.micrometer.observation.Observation;
import jakarta.annotation.PreDestroy;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
 *   <li>draft fails parsing ({@link ReportDraftParser}), semantic validation including citation
 *       existence ({@link ReportValidator#validateDraft}) or the final check of the assembled report
 *       ({@link ReportValidator#validateReport}): one correction call
 *       ({@link InvestigatorAgent#correct}) with up to {@value #MAX_REPORTED_ERRORS} errors, in the same
 *       chat memory and before the same deadline; its draft gets the same checks and, if it fails too,
 *       the fallback report lists both rounds' distinct errors (at most {@value #MAX_REPORTED_ERRORS}),
 *       {@code INCONCLUSIVE}. Each rejected draft is audited ({@link AuditService#recordDraftRejection});
 *   <li>timeout: fallback report, {@code INCONCLUSIVE}; the agent thread is interrupted;
 *   <li>tool-calling round-trip limit: fallback report, {@code INCONCLUSIVE};
 *   <li>Token_Budget reached ({@link TokenBudgetChatModel} refused a request, or the budget is used up
 *       before the correction call): fallback report with a token-budget open question,
 *       {@code INCONCLUSIVE} (Req 6.5);
 *   <li>any other exception: fallback report, {@code FAILED}.
 * </ul>
 *
 * <p>The terminal status is set with a compare-and-set {@link InvestigationRepository#transition}; only
 * the caller that wins it removes the context, stores the report ({@link ReportRepository#saveIfAbsent}),
 * sets the Incident to {@code REPORTED}, writes the report to the log ({@link ReportPublisher}) and
 * evicts the chat memory. A report that is not stored because one already exists is kept as a distinct
 * audit row ({@link AuditService#recordReport}, Req 12.7). The {@code accepting} flag is true only between the {@code RUNNING}
 * transition and the end of the agent call, so the {@code ToolGuard} refuses tool calls in every
 * other state (Req 11.13). An agent that returns
 * after the investigation ended has its result discarded with a warning (Req 14.10).
 */
@Component
public class InvestigationOrchestrator {

    static final String DRAFT_INVALID = "the report draft failed validation";
    static final String ROUND_TRIP_LIMIT = "tool-calling limit reached";
    static final String LLM_FAILED = "LLM call failed: ";
    static final String TIMED_OUT = "timed out after ";
    static final String INTERRUPTED = "investigation was interrupted";
    static final String INTERNAL_ERROR = "internal error: ";

    /** Most validation errors sent in the correction prompt and listed in the fallback report. */
    static final int MAX_REPORTED_ERRORS = 10;

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
    private final ReportValidator validator;
    private final ReportAssembler assembler;
    private final FallbackReportBuilder fallback;
    private final ReportPublisher publisher;
    private final InvestigatorProperties properties;
    private final Clock clock;
    private final AuditService audit;
    private final InvestigationObservability observability;
    private final InvestigatorMetrics metrics;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public InvestigationOrchestrator(InvestigationRepository investigations, IncidentRepository incidents,
            ReportRepository reports, EvidenceRepository evidenceRepository, EvidenceService evidenceService,
            InvestigationContextRegistry contexts, InvestigatorAgent agent, PromptInputs promptInputs,
            ReportDraftParser parser, ReportValidator validator, ReportAssembler assembler,
            FallbackReportBuilder fallback, ReportPublisher publisher, InvestigatorProperties properties,
            Clock clock, AuditService audit, InvestigationObservability observability, InvestigatorMetrics metrics) {
        this.investigations = investigations;
        this.incidents = incidents;
        this.reports = reports;
        this.evidenceRepository = evidenceRepository;
        this.evidenceService = evidenceService;
        this.contexts = contexts;
        this.agent = agent;
        this.promptInputs = promptInputs;
        this.parser = parser;
        this.validator = validator;
        this.assembler = assembler;
        this.fallback = fallback;
        this.publisher = publisher;
        this.properties = properties;
        this.clock = clock;
        this.audit = audit;
        this.observability = observability;
        this.metrics = metrics;
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
                    traceId, alert.id(), incident.detectedAt(), properties.budget().maxToolCalls(),
                    properties.budget().maxLlmTokens());
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
        putMdc(ctx);
        try {
            if (!investigations.transition(incidentId, InvestigationStatus.PENDING, InvestigationStatus.RUNNING)) {
                log.warn("investigation is not PENDING; not running it incident_id={}", incidentId);
                return;
            }
            // The investigation span (Req 1.1, 1.4); its scope is also opened on each agent thread.
            long startedNanos = System.nanoTime();
            Observation observation = observability.investigation(incidentId, incidents.find(incidentId).orElse(null))
                    .start();
            try (Observation.Scope scope = observation.openScope()) {
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
                observation.lowCardinalityKeyValue(InvestigationObservability.STATUS, outcome.status().name());
                conclude(ctx, InvestigationStatus.RUNNING, outcome, concluded, startedNanos);
            } catch (RuntimeException e) {
                observation.error(e);
                throw e;
            } finally {
                observation.stop();
            }
        } finally {
            clearMdc();
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
        AgentResult result = await(ctx, agentCall(ctx, concluded,
                () -> agent.investigate(incidentId, incidentJson, PromptInputs.isoInstant(ctx.windowStart()),
                        PromptInputs.isoInstant(ctx.windowEnd()), ctx.alertEvidenceId(),
                        promptInputs.reportSchema())));
        if (!(result instanceof AgentResult.Answer answer)) {
            stopAccepting(ctx, concluded);
            return outcomeFor(ctx, result);
        }
        DraftCheck first = checkDraft(ctx, answer.raw(), 1);
        if (first.valid()) {
            stopAccepting(ctx, concluded);
            return first.outcome();
        }

        if (ctx.tokenBudgetReached()) {
            // No correction request once the Token_Budget is used up (Req 6.5).
            stopAccepting(ctx, concluded);
            audit.recordDraftRejection(new AuditService.DraftRejection(incidentId, 1, first.errors(),
                    AuditService.DraftRejection.FALLBACK));
            return tokenBudgetOutcome(ctx, first.errors());
        }

        // One correction round in the same chat memory, with the tools still accepted and the same
        // deadline (Req 4.3, 4.5).
        audit.recordDraftRejection(new AuditService.DraftRejection(incidentId, 1, first.errors(),
                AuditService.DraftRejection.RETRY));
        String errors = correctionErrors(first.errors());
        log.info("retrying the report draft with its validation errors incident_id={} error_count={}",
                incidentId, first.errors().size());
        AgentResult retry = await(ctx, agentCall(ctx, concluded, () -> agent.correct(incidentId, errors)));
        stopAccepting(ctx, concluded);
        if (!(retry instanceof AgentResult.Answer corrected)) {
            return outcomeFor(ctx, retry);
        }
        DraftCheck second = checkDraft(ctx, corrected.raw(), 2);
        if (second.valid()) {
            return second.outcome();
        }
        audit.recordDraftRejection(new AuditService.DraftRejection(incidentId, 2, second.errors(),
                AuditService.DraftRejection.FALLBACK));
        List<String> both = Stream.concat(first.errors().stream(), second.errors().stream())
                .distinct().limit(MAX_REPORTED_ERRORS).toList();
        return fallbackOutcome(incidentId, InvestigationStatus.INCONCLUSIVE, DRAFT_INVALID, both);
    }

    /** Runs {@code call} on an agent thread and waits for it until the investigation deadline. */
    private AgentResult await(InvestigationContext ctx, Callable<String> call) {
        if (remainingNanos(ctx) == 0) {
            return new AgentResult.TimedOut();
        }
        Future<String> future = executor.submit(call);
        try {
            return new AgentResult.Answer(future.get(remainingNanos(ctx), TimeUnit.NANOSECONDS));
        } catch (TimeoutException e) {
            future.cancel(true);
            return new AgentResult.TimedOut();
        } catch (ExecutionException e) {
            return new AgentResult.Failed(e.getCause() != null ? e.getCause() : e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return new AgentResult.Interrupted();
        }
    }

    /** The first {@value #MAX_REPORTED_ERRORS} errors, one per line, for the correction prompt. */
    static String correctionErrors(List<String> errors) {
        return errors.stream().limit(MAX_REPORTED_ERRORS).map(e -> "- " + e).collect(Collectors.joining("\n"));
    }

    private Callable<String> agentCall(InvestigationContext ctx, AtomicBoolean concluded, Callable<String> call) {
        String incidentId = ctx.incidentId();
        // Captured on the run thread, so tool and LLM spans on the agent thread nest under the
        // investigation span (Req 1.2, 1.3).
        Observation parent = observability.current();
        return () -> {
            putMdc(ctx);
            try (Observation.Scope scope = InvestigationObservability.openScope(parent)) {
                return call.call();
            } finally {
                if (concluded.get()) {
                    // The orchestrator already ended the investigation: drop the result and the memory
                    // this late call may have recreated (Req 14.10).
                    log.warn("agent returned after the investigation ended; result discarded incident_id={}",
                            incidentId);
                    agent.evictChatMemory(incidentId);
                }
                clearMdc();
            }
        };
    }

    /**
     * Puts {@code incident_id} and {@code trace_id} in the MDC of the current thread, so every log
     * entry written on the run thread and the agent thread (tools included) carries them (Req 18.2).
     */
    private static void putMdc(InvestigationContext ctx) {
        MDC.put(LogFields.INCIDENT_ID, ctx.incidentId());
        MDC.put(LogFields.TRACE_ID, ctx.traceId());
    }

    /** Removes the investigation fields from the MDC of the current thread. */
    private static void clearMdc() {
        MDC.remove(LogFields.INCIDENT_ID);
        MDC.remove(LogFields.TRACE_ID);
    }

    private Outcome outcomeFor(InvestigationContext ctx, AgentResult result) {
        String incidentId = ctx.incidentId();
        return switch (result) {
            case AgentResult.Answer answer -> throw new IllegalStateException("an answer is checked as a draft");
            case AgentResult.TimedOut timedOut -> {
                String reason = TIMED_OUT + humanDuration(properties.budget().timeout());
                log.warn("investigation timed out incident_id={} reason=\"{}\"", incidentId, reason);
                yield fallbackOutcome(incidentId, InvestigationStatus.INCONCLUSIVE, reason, List.of());
            }
            case AgentResult.Failed failed when isTokenBudget(failed.cause()) -> tokenBudgetOutcome(ctx, List.of());
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

    /**
     * Parses and validates the draft against the stored Evidence_IDs, assembles the report and checks it
     * again (Req 4.1, 14.4, 14.6, 14.8, 15). Returns the outcome, or the errors if any step failed.
     */
    private DraftCheck checkDraft(InvestigationContext ctx, String raw, int attempt) {
        String incidentId = ctx.incidentId();
        ParseResult parsed = parser.parse(raw);
        List<EvidenceRef> refs = evidenceService.refs(incidentId);
        Set<String> known = refs.stream().map(EvidenceRef::id).collect(Collectors.toUnmodifiableSet());
        List<String> errors = switch (parsed) {
            case ParseResult.Invalid invalid -> invalid.errors();
            case ParseResult.Ok ok -> validator.validateDraft(ok.draft(), incidentId, known).violations();
        };
        String stage = parsed instanceof ParseResult.Ok ? "validation" : "parsing";
        if (errors.isEmpty() && parsed instanceof ParseResult.Ok ok) {
            boolean exhausted = ctx.budgetExhausted().get();
            InvestigationStatus status = exhausted ? InvestigationStatus.INCONCLUSIVE : InvestigationStatus.COMPLETED;
            List<String> extra = exhausted ? List.of(budgetQuestion(ctx.budget())) : List.of();
            try {
                InvestigationReport report = assembler.assemble(ok.draft(), ctx, status, extra, refs,
                        clock.instant());
                errors = validator.validateReport(report, known).violations();
                if (errors.isEmpty()) {
                    return new DraftCheck(new Outcome(status, report,
                        attempt == 1 ? InvestigatorMetrics.REPORT_VALID : InvestigatorMetrics.REPORT_CORRECTED),
                        List.of());
                }
                stage = "report validation";
            } catch (IllegalArgumentException e) {
                errors = List.of(e.getMessage());
                stage = "assembly";
            }
        }
        log.warn("report draft rejected incident_id={} attempt={} stage=\"{}\" error_count={}", incidentId,
                attempt, stage, errors.size());
        return new DraftCheck(null, errors);
    }

    /** Evidence-only {@code INCONCLUSIVE} report naming the Token_Budget as the reason (Req 6.5). */
    private Outcome tokenBudgetOutcome(InvestigationContext ctx, List<String> details) {
        log.warn("token budget reached; ending the investigation incident_id={} llm_tokens={} budget={}",
                ctx.incidentId(), ctx.llmTokens(), ctx.maxLlmTokens());
        return fallbackOutcome(ctx.incidentId(), InvestigationStatus.INCONCLUSIVE,
                tokenBudgetQuestion(ctx.maxLlmTokens()), details.stream().limit(MAX_REPORTED_ERRORS).toList());
    }

    /** Open question of a report ended by the Token_Budget (Req 6.5). */
    static String tokenBudgetQuestion(long budget) {
        return "LLM token budget of " + budget + " tokens was reached; the investigation may be incomplete.";
    }

    /** True if {@code error} or one of its causes is a {@link TokenBudgetExceededException}. */
    static boolean isTokenBudget(Throwable error) {
        int depth = 0;
        for (Throwable t = error; t != null && depth < 10; t = t.getCause(), depth++) {
            if (t instanceof TokenBudgetExceededException) {
                return true;
            }
        }
        return false;
    }

    /** Open question added when the Tool_Call_Budget was reached (Req 14.4). */
    static String budgetQuestion(int budget) {
        return "Tool-call budget of " + budget + " calls was reached; the investigation may be incomplete.";
    }

    private Outcome fallbackOutcome(String incidentId, InvestigationStatus status, String reason, List<String> details) {
        String service = incidents.find(incidentId).map(Incident::affectedService).orElse(null);
        return new Outcome(status, fallbackReport(incidentId, service, status, reason, details),
                InvestigatorMetrics.REPORT_FALLBACK);
    }

    /**
     * Builds the fallback report and checks it with {@code validateReport} (Req 14.12). There is nothing
     * further to fall back to, so an invalid fallback report is logged and still stored, keeping one
     * report per Investigation (Req 14.9).
     */
    private InvestigationReport fallbackReport(String incidentId, String service, InvestigationStatus status,
            String reason, List<String> details) {
        List<Evidence> stored = evidenceRepository.findByIncident(incidentId);
        InvestigationReport report = fallback.build(incidentId, service, status, reason, details, stored,
                clock.instant());
        ValidationResult check = validator.validateReport(report,
                stored.stream().map(Evidence::id).collect(Collectors.toUnmodifiableSet()));
        if (!check.valid()) {
            log.error("fallback report failed validation; storing it anyway incident_id={} violations={}",
                    incidentId, check.violations());
        }
        return report;
    }

    /** Writes the result if this caller wins the terminal transition (Req 14.9, 14.10). */
    private void conclude(InvestigationContext ctx, InvestigationStatus from, Outcome outcome,
            AtomicBoolean concluded, long startedNanos) {
        String incidentId = ctx.incidentId();
        stopAccepting(ctx, concluded);
        investigations.recordToolCalls(incidentId, ctx.used().get());
        if (!investigations.transition(incidentId, from, outcome.status())) {
            log.warn("investigation already ended; late result discarded incident_id={} status={}",
                    incidentId, outcome.status());
            return;
        }
        metrics.investigationFinished(outcome.status(), Duration.ofNanos(System.nanoTime() - startedNanos));
        // Removed before the report becomes visible, so a reader that sees the report also sees no
        // context; tool calls were already refused since stopAccepting (Req 11.13).
        contexts.remove(incidentId);
        boolean stored = storeReport(outcome.report(), outcome.validationStatus());
        if (!stored) {
            log.warn("a report is already stored; keeping it incident_id={}", incidentId);
            audit.recordReport(outcome.report());
        }
        incidents.updateStatus(incidentId, IncidentStatus.REPORTED);
        if (stored) {
            publish(incidentId, outcome.report());
        }
        agent.evictChatMemory(incidentId);
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
            InvestigationReport report = fallbackReport(incidentId, incident.affectedService(),
                    InvestigationStatus.FAILED, reason, List.of());
            boolean stored = storeReport(report, InvestigatorMetrics.REPORT_FALLBACK);
            if (!stored) {
                audit.recordReport(report);
            }
            incidents.updateStatus(incidentId, IncidentStatus.REPORTED);
            if (stored) {
                publish(incidentId, report);
            }
        } finally {
            contexts.remove(incidentId);
        }
    }

    /** Stores the report unless one exists, counting a stored one by {@code validationStatus} (Req 2.4). */
    private boolean storeReport(InvestigationReport report, String validationStatus) {
        boolean stored = reports.saveIfAbsent(report);
        if (stored) {
            metrics.reportGenerated(validationStatus);
        }
        return stored;
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

    /** Terminal status, the report to store with it, and its {@code validation_status} meter tag. */
    private record Outcome(InvestigationStatus status, InvestigationReport report, String validationStatus) {
    }

    /** A checked draft: the outcome if it is valid, otherwise the errors. */
    private record DraftCheck(Outcome outcome, List<String> errors) {

        boolean valid() {
            return outcome != null;
        }
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
