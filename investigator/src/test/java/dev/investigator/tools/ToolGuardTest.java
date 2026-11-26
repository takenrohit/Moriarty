package dev.investigator.tools;

import static dev.investigator.testsupport.ScriptedChatModel.finalAnswer;
import static dev.investigator.testsupport.ScriptedChatModel.lastToolResult;
import static dev.investigator.testsupport.ScriptedChatModel.toolCall;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.investigator.config.InvestigatorProperties;
import dev.investigator.domain.EvidenceType;
import dev.investigator.domain.Incident;
import dev.investigator.domain.IncidentStatus;
import dev.investigator.domain.InvestigationReport;
import dev.investigator.domain.InvestigationStatus;
import dev.investigator.domain.Severity;
import dev.investigator.investigation.InMemoryInvestigationContextRegistry;
import dev.investigator.investigation.InvestigationContext;
import dev.investigator.investigation.InvestigationContextRegistry;
import dev.investigator.investigation.InvestigationOrchestrator;
import dev.investigator.store.EvidenceRepository;
import dev.investigator.store.IncidentRepository;
import dev.investigator.store.InvestigationRepository;
import dev.investigator.store.ReportRepository;
import dev.investigator.testsupport.InvestigatorSpringBootTest;
import dev.investigator.testsupport.ScriptedChatModel;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Unit tests for the Tool_Guard (Req 11, 14.4, 19.1; NFR-40): allow-list, window clamp, argument
 * limits at their boundaries, the tool-call budget and the "not running" refusal. The guard is built
 * directly with an in-memory context registry; {@link WithOrchestrator} uses the Spring context only
 * for the cases that need a real investigation run.
 */
class ToolGuardTest {

    private static final Instant WINDOW_START = Instant.parse("2026-11-23T13:40:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-11-23T14:50:00Z");
    private static final String WINDOW_HINT =
            "use a range inside the investigation window [2026-11-23T13:40:00Z, 2026-11-23T14:50:00Z]";
    private static final String INCIDENT_ID = "inc-0123456789ab";
    private static final int BUDGET = 15;

    private InvestigationContextRegistry registry;
    private ToolGuard guard;
    private InvestigationContext ctx;

    @BeforeEach
    void setUp() {
        registry = new InMemoryInvestigationContextRegistry();
        guard = new ToolGuard(registry, properties());
        ctx = new InvestigationContext(INCIDENT_ID, WINDOW_START, WINDOW_END, WINDOW_END, "0".repeat(32),
                "ev-" + INCIDENT_ID + "-1", Instant.parse("2026-11-23T14:40:00Z"), BUDGET);
        registry.register(ctx);
    }

    // --- Allow-list (Req 11.1, 11.2) ---

    @Test
    void allowListHitReturnsTheTrimmedService() {
        assertThat(guard.requireAllowedService("checkout-api")).isEqualTo("checkout-api");
        assertThat(guard.requireAllowedService("  payment-gateway\t")).isEqualTo("payment-gateway");
    }

    @Test
    void allowListMissIsRejectedWithTheSortedAllowList() {
        String allowed = "use one of the allowed services: checkout-api, payment-gateway, payment-provider";
        assertRejected(() -> guard.requireAllowedService("inventory-api"), e -> {
            assertThat(e.error()).isEqualTo(ToolGuard.SERVICE_NOT_ALLOWED);
            assertThat(e.hint()).isEqualTo(allowed);
            assertThat(e.toResult()).doesNotContain("inventory-api");
        });
        // The match is exact: no case folding.
        assertRejected(() -> guard.requireAllowedService("Checkout-API"),
                e -> assertThat(e.error()).isEqualTo(ToolGuard.SERVICE_NOT_ALLOWED));
        assertRejected(() -> guard.requireAllowedService("  "), e -> {
            assertThat(e.error()).isEqualTo("service is required");
            assertThat(e.hint()).isEqualTo(allowed);
        });
        assertRejected(() -> guard.requireAllowedService(null),
                e -> assertThat(e.error()).isEqualTo("service is required"));
    }

    // --- Window clamp (Req 11.3, 11.4) ---

    @Test
    void rangeInsideTheWindowIsKeptUnclamped() {
        TimeRange whole = guard.clampToWindow(ctx, "2026-11-23T13:40:00Z", "2026-11-23T14:50:00Z");
        assertThat(whole).isEqualTo(new TimeRange(WINDOW_START, WINDOW_END, false));

        TimeRange inner = guard.clampToWindow(ctx, "2026-11-23T14:00:00Z", "2026-11-23T14:45:00+00:00");
        assertThat(inner).isEqualTo(new TimeRange(Instant.parse("2026-11-23T14:00:00Z"),
                Instant.parse("2026-11-23T14:45:00Z"), false));
    }

    @Test
    void rangeCrossingAWindowEdgeIsClampedToTheEdge() {
        TimeRange early = guard.clampToWindow(ctx, "2026-11-23T13:00:00Z", "2026-11-23T14:00:00Z");
        assertThat(early).isEqualTo(new TimeRange(WINDOW_START, Instant.parse("2026-11-23T14:00:00Z"), true));

        TimeRange late = guard.clampToWindow(ctx, "2026-11-23T14:30:00Z", "2026-11-23T16:00:00Z");
        assertThat(late).isEqualTo(new TimeRange(Instant.parse("2026-11-23T14:30:00Z"), WINDOW_END, true));

        TimeRange both = guard.clampToWindow(ctx, "2026-11-23T12:00:00Z", "2026-11-23T16:00:00Z");
        assertThat(both).isEqualTo(new TimeRange(WINDOW_START, WINDOW_END, true));

        // One second inside each edge survives the clamp.
        TimeRange lastSecond = guard.clampToWindow(ctx, "2026-11-23T14:49:59Z", "2026-11-23T15:00:00Z");
        assertThat(lastSecond).isEqualTo(new TimeRange(WINDOW_END.minusSeconds(1), WINDOW_END, true));
        TimeRange firstSecond = guard.clampToWindow(ctx, "2026-11-23T13:00:00Z", "2026-11-23T13:40:01Z");
        assertThat(firstSecond).isEqualTo(new TimeRange(WINDOW_START, WINDOW_START.plusSeconds(1), true));
    }

    @Test
    void rangeEntirelyOutsideTheWindowIsRejectedStatingTheWindow() {
        for (String[] range : new String[][] {
                {"2026-11-23T12:00:00Z", "2026-11-23T13:00:00Z"}, // before
                {"2026-11-23T15:00:00Z", "2026-11-23T16:00:00Z"}, // after
                {"2026-11-23T13:00:00Z", "2026-11-23T13:40:00Z"}, // ends at the window start
                {"2026-11-23T14:50:00Z", "2026-11-23T15:30:00Z"}, // starts at the window end
        }) {
            assertRejected(() -> guard.clampToWindow(ctx, range[0], range[1]), e -> {
                assertThat(e.error()).isEqualTo(ToolGuard.OUTSIDE_WINDOW);
                assertThat(e.hint()).isEqualTo(WINDOW_HINT);
            });
        }
    }

    @Test
    void startAtOrAfterEndIsRejected() {
        assertRejected(() -> guard.clampToWindow(ctx, "2026-11-23T14:00:00Z", "2026-11-23T14:00:00Z"), e -> {
            assertThat(e.error()).isEqualTo(ToolGuard.START_NOT_BEFORE_END);
            assertThat(e.hint()).isEqualTo(WINDOW_HINT);
        });
        // Same instant written in two offsets.
        assertRejected(() -> guard.clampToWindow(ctx, "2026-11-23T19:30:00+05:30", "2026-11-23T14:00:00Z"),
                e -> assertThat(e.error()).isEqualTo(ToolGuard.START_NOT_BEFORE_END));
        assertRejected(() -> guard.clampToWindow(ctx, "2026-11-23T14:30:00Z", "2026-11-23T14:00:00Z"),
                e -> assertThat(e.error()).isEqualTo(ToolGuard.START_NOT_BEFORE_END));
    }

    // --- Query and symptom limits (Req 11.6, 11.7) ---

    @Test
    void queryOf1000CharactersIsAcceptedAnd1001IsRejected() {
        String max = "q".repeat(1000);
        assertThat(guard.requireQuery(max)).isSameAs(max);
        assertRejected(() -> guard.requireQuery(max + "q"),
                e -> assertThat(e.error()).isEqualTo("query is longer than 1000 characters"));
        assertRejected(() -> guard.requireQuery(" \t "), e -> assertThat(e.error()).isEqualTo("query is required"));
        assertRejected(() -> guard.requireQuery(null), e -> assertThat(e.error()).isEqualTo("query is required"));
    }

    @Test
    void symptomOf200CharactersIsAcceptedAnd201IsRejected() {
        String max = "s".repeat(200);
        assertThat(guard.requireSymptom(max)).isSameAs(max);
        assertRejected(() -> guard.requireSymptom(max + "s"),
                e -> assertThat(e.error()).isEqualTo("symptom is longer than 200 characters"));
        assertRejected(() -> guard.requireSymptom(""), e -> assertThat(e.error()).isEqualTo("symptom is required"));
    }

    // --- Look-back (Req 11.8, 11.9) ---

    @Test
    void lookbackBelowOneIsRejected() {
        assertRejected(() -> guard.lookback(ctx, 0), e -> {
            assertThat(e.error()).isEqualTo("lookbackHours must be at least 1");
            assertThat(e.hint()).isEqualTo("use a value from 1 to 24");
        });
    }

    @Test
    void lookbackFrom1To24IsKeptAndAbove24IsClampedTo24() {
        assertThat(guard.lookback(ctx, 1))
                .isEqualTo(new Lookback(1, WINDOW_END.minus(Duration.ofHours(1)), WINDOW_END, false));
        assertThat(guard.lookback(ctx, 24))
                .isEqualTo(new Lookback(24, WINDOW_END.minus(Duration.ofHours(24)), WINDOW_END, false));
        assertThat(guard.lookback(ctx, 25))
                .isEqualTo(new Lookback(24, WINDOW_END.minus(Duration.ofHours(24)), WINDOW_END, true));
    }

    // --- Budget (Req 11.10, 11.11) ---

    @Test
    void fifteenCallsAreAllowedAndThe16thIsRefusedAndMarksTheBudgetExhausted() {
        ctx.accepting().set(true);
        for (int i = 1; i <= BUDGET; i++) {
            assertThat(guard.begin(INCIDENT_ID, "getMetric")).isSameAs(ctx);
            assertThat(ctx.used().get()).isEqualTo(i);
        }
        assertThat(ctx.budgetExhausted().get()).isFalse();

        assertRejected(() -> guard.begin(INCIDENT_ID, "getMetric"), e -> {
            assertThat(e.error()).isEqualTo("tool-call budget is used up (15 calls)");
            assertThat(e.hint()).isEqualTo(ToolGuard.RETURN_REPORT_HINT);
        });
        assertThat(ctx.used().get()).isEqualTo(16);
        assertThat(ctx.budgetExhausted().get()).isTrue();
    }

    @Test
    void rejectedInvocationsCountAgainstTheBudget() {
        ctx.accepting().set(true);
        for (int i = 0; i < BUDGET - 1; i++) {
            InvestigationContext begun = guard.begin(INCIDENT_ID, "getMetric");
            assertRejected(() -> guard.requireAllowedService("unknown"), e -> { });
            assertThat(begun.used().get()).isEqualTo(i + 1);
        }
        assertThat(guard.malformedArguments(INCIDENT_ID, "getLogs"))
                .contains(ToolGuard.MALFORMED_ARGUMENTS);
        assertThat(ctx.used().get()).isEqualTo(BUDGET);

        assertThat(guard.malformedArguments(INCIDENT_ID, "getLogs")).contains(ToolGuard.BUDGET_EXHAUSTED);
        assertThat(ctx.budgetExhausted().get()).isTrue();
        assertRejected(() -> guard.begin(INCIDENT_ID, "getMetric"),
                e -> assertThat(e.error()).startsWith(ToolGuard.BUDGET_EXHAUSTED));
    }

    // --- Not running (Req 11.13) ---

    @Test
    void invocationWhilePendingIsRefusedAndNotCounted() {
        // A new context is not accepting: the Investigation is still PENDING.
        assertRejected(() -> guard.begin(INCIDENT_ID, "getMetric"), e -> {
            assertThat(e.error()).isEqualTo(ToolGuard.NOT_RUNNING);
            assertThat(e.hint()).isEqualTo(ToolGuard.RETURN_REPORT_HINT);
        });
        assertThat(ctx.used().get()).isZero();
    }

    @Test
    void invocationAfterTheInvestigationEndedIsRefused() {
        ctx.accepting().set(true);
        guard.begin(INCIDENT_ID, "getMetric");

        // The orchestrator stops accepting before the terminal transition ...
        ctx.accepting().set(false);
        assertRejected(() -> guard.begin(INCIDENT_ID, "getMetric"),
                e -> assertThat(e.error()).isEqualTo(ToolGuard.NOT_RUNNING));
        // ... and removes the context after it.
        registry.remove(INCIDENT_ID);
        assertRejected(() -> guard.begin(INCIDENT_ID, "getMetric"),
                e -> assertThat(e.error()).isEqualTo(ToolGuard.NOT_RUNNING));
        assertRejected(() -> guard.begin(null, "getMetric"),
                e -> assertThat(e.error()).isEqualTo(ToolGuard.NOT_RUNNING));
        assertThat(ctx.used().get()).isEqualTo(1);
    }

    /** Cases that need a real investigation run through the orchestrator and the agent. */
    @Nested
    @InvestigatorSpringBootTest
    class WithOrchestrator {

        private static final Instant DETECTED_AT = Instant.parse("2026-11-23T14:40:00Z");
        private static final String METRIC_ARGS = "{\"service\":\"checkout-api\",\"promql\":\"up\","
                + "\"start\":\"2026-11-23T14:00:00Z\",\"end\":\"2026-11-23T14:45:00Z\"}";

        @Autowired InvestigationOrchestrator orchestrator;
        @Autowired ScriptedChatModel model;
        @Autowired ToolGuard springGuard;
        @Autowired IncidentRepository incidents;
        @Autowired InvestigationRepository investigations;
        @Autowired ReportRepository reports;
        @Autowired EvidenceRepository evidence;
        @Autowired InvestigatorProperties properties;

        @BeforeEach
        void resetModel() {
            model.reset();
        }

        @Test
        void budgetExhaustedValidDraftGivesInconclusiveReportWithTheBudgetQuestion() throws Exception {
            int budget = properties.budget().maxToolCalls();
            Incident incident = newIncident();
            for (int i = 0; i <= budget; i++) {
                model.enqueue(toolCall("getMetric", METRIC_ARGS));
            }
            AtomicReference<String> refusal = new AtomicReference<>();
            model.enqueue(request -> {
                refusal.set(lastToolResult(request).map(ToolExecutionResultMessage::text).orElse(""));
                return finalAnswer(validDraft(incident.id())).apply(request);
            });

            orchestrator.start(incident);

            InvestigationReport report = awaitReport(incident.id());
            assertThat(refusal.get()).contains(ToolGuard.BUDGET_EXHAUSTED).contains(ToolGuard.RETURN_REPORT_HINT);
            assertThat(report.status()).isEqualTo(InvestigationStatus.INCONCLUSIVE);
            assertThat(report.summary()).isEqualTo("Checkout errors rose after payment-provider timeouts.");
            assertThat(report.openQuestions()).containsExactly("Is the provider degraded?",
                    "Tool-call budget of " + budget + " calls was reached; the investigation may be incomplete.");
            assertThat(investigations.find(incident.id()).orElseThrow())
                    .satisfies(inv -> assertThat(inv.status()).isEqualTo(InvestigationStatus.INCONCLUSIVE))
                    .satisfies(inv -> assertThat(inv.toolCallsUsed()).isEqualTo(budget + 1));
            // Only the calls within the budget reached a client and stored Evidence (Req 11.12).
            assertThat(evidence.findByIncident(incident.id()))
                    .filteredOn(e -> e.type() == EvidenceType.METRIC).hasSize(budget);
        }

        @Test
        void invocationAfterCompletedIsRefused() throws Exception {
            Incident incident = newIncident();
            model.enqueue(finalAnswer(validDraft(incident.id())));

            orchestrator.start(incident);

            InvestigationReport report = awaitReport(incident.id());
            assertThat(report.status()).isEqualTo(InvestigationStatus.COMPLETED);
            assertThat(investigations.find(incident.id()).orElseThrow().status())
                    .isEqualTo(InvestigationStatus.COMPLETED);
            assertRejected(() -> springGuard.begin(incident.id(), "getMetric"),
                    e -> assertThat(e.error()).isEqualTo(ToolGuard.NOT_RUNNING));
            assertThat(investigations.find(incident.id()).orElseThrow().toolCallsUsed()).isZero();
        }

        private InvestigationReport awaitReport(String incidentId) throws InterruptedException {
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline) {
                var found = reports.find(incidentId);
                if (found.isPresent()) {
                    return found.get();
                }
                Thread.sleep(10);
            }
            throw new AssertionError("no report for " + incidentId + " within 10 s");
        }

        private Incident newIncident() {
            byte[] bytes = new byte[6];
            ThreadLocalRandom.current().nextBytes(bytes);
            Incident incident = new Incident("inc-" + HexFormat.of().formatHex(bytes),
                    "HighErrorRate on checkout-api", Severity.CRITICAL, "checkout-api", DETECTED_AT,
                    "HighErrorRate", Map.of("env", "prod"), List.of(), "f".repeat(64), IncidentStatus.OPEN);
            incidents.save(incident);
            return incident;
        }

        private static String validDraft(String incidentId) {
            String alert = "ev-" + incidentId + "-1";
            return """
                    {
                      "incidentId": "%1$s",
                      "summary": "Checkout errors rose after payment-provider timeouts.",
                      "timeline": [
                        { "at": "2026-11-23T14:40:00Z", "description": "Alert fired", "evidenceIds": ["%2$s"] }
                      ],
                      "hypotheses": [
                        { "statement": "Provider timeouts", "confidence": "MEDIUM",
                          "supportingEvidenceIds": ["%2$s"], "contradictingEvidenceIds": [],
                          "establishedCause": false }
                      ],
                      "actions": [
                        { "order": 1, "type": "INVESTIGATE", "description": "Check provider status",
                          "requiresApproval": false, "evidenceIds": ["%2$s"] }
                      ],
                      "openQuestions": ["Is the provider degraded?"]
                    }
                    """.formatted(incidentId, alert);
        }
    }

    private static void assertRejected(ThrowingCallable call, Consumer<ToolRejectedException> check) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ToolRejectedException.class, check);
    }

    private static InvestigatorProperties properties() {
        Map<String, InvestigatorProperties.AllowedService> services = new LinkedHashMap<>();
        // Inserted unsorted, to show the hint lists them sorted.
        services.put("payment-provider", new InvestigatorProperties.AllowedService(List.of()));
        services.put("checkout-api", new InvestigatorProperties.AllowedService(List.of("payment-gateway")));
        services.put("payment-gateway", new InvestigatorProperties.AllowedService(List.of("payment-provider")));
        return new InvestigatorProperties(
                ZoneId.of("Asia/Kolkata"),
                new InvestigatorProperties.Window(Duration.ofMinutes(60), Duration.ofMinutes(10), Duration.ofHours(6)),
                new InvestigatorProperties.Budget(BUDGET, Duration.ofMinutes(3), 200_000),
                new InvestigatorProperties.Tools(1000, 200, 24, Duration.ofHours(6), 4096, 300,
                        Duration.ofSeconds(15), 500, 5, 50, 3, Duration.ofSeconds(2), 20),
                new InvestigatorProperties.Agent(64),
                services,
                new InvestigatorProperties.Mock(Instant.parse("2026-11-23T14:40:00Z")));
    }
}
