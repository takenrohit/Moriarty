package dev.investigator.investigation;

import static dev.investigator.testsupport.ScriptedChatModel.finalAnswer;
import static dev.investigator.testsupport.ScriptedChatModel.toolCall;
import static org.assertj.core.api.Assertions.assertThat;

import dev.investigator.audit.NoOpAuditService;
import dev.investigator.config.InvestigatorProperties;
import dev.investigator.domain.ActionType;
import dev.investigator.domain.Evidence;
import dev.investigator.domain.EvidenceType;
import dev.investigator.domain.Incident;
import dev.investigator.domain.IncidentStatus;
import dev.investigator.domain.Investigation;
import dev.investigator.domain.InvestigationReport;
import dev.investigator.domain.InvestigationStatus;
import dev.investigator.domain.RecommendedAction;
import dev.investigator.domain.Severity;
import dev.investigator.domain.TimelineEntry;
import dev.investigator.evidence.EvidenceService;
import dev.investigator.report.FallbackReportBuilder;
import dev.investigator.report.ReportAssembler;
import dev.investigator.report.ReportDraftParser;
import dev.investigator.report.ReportPublisher;
import dev.investigator.report.ReportValidator;
import dev.investigator.store.EvidenceRepository;
import dev.investigator.store.IncidentRepository;
import dev.investigator.store.InvestigationRepository;
import dev.investigator.store.ReportRepository;
import dev.investigator.testsupport.InvestigatorSpringBootTest;
import dev.investigator.testsupport.ScriptedChatModel;
import dev.langchain4j.memory.ChatMemory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Smoke tests for the Investigation_Orchestrator with the Scripted_Chat_Model: each outcome of the
 * design's outcome table ends with one stored report, the matching status and the Incident
 * {@code REPORTED} (Req 6.2, 6.6, 7.1, 9.7, 14.2 to 14.8, 14.10).
 */
@InvestigatorSpringBootTest
class InvestigationOrchestratorSmokeTest {

    private static final Instant DETECTED_AT = Instant.parse("2026-11-23T14:40:00Z");
    private static final String METRIC_ARGS = "{\"service\":\"checkout-api\",\"promql\":\"up\","
            + "\"start\":\"2026-11-23T14:00:00Z\",\"end\":\"2026-11-23T14:45:00Z\"}";

    @Autowired InvestigationOrchestrator orchestrator;
    @Autowired ScriptedChatModel model;
    @Autowired IncidentRepository incidents;
    @Autowired InvestigationRepository investigations;
    @Autowired ReportRepository reports;
    @Autowired EvidenceRepository evidenceRepository;
    @Autowired EvidenceService evidenceService;
    @Autowired InvestigationContextRegistry contexts;
    @Autowired PromptInputs promptInputs;
    @Autowired ReportDraftParser parser;
    @Autowired ReportValidator validator;
    @Autowired ReportAssembler assembler;
    @Autowired FallbackReportBuilder fallback;
    @Autowired ReportPublisher publisher;
    @Autowired InvestigatorProperties properties;
    @Autowired Clock clock;

    @BeforeEach
    void resetModel() {
        model.reset();
    }

    @Test
    void validDraftCompletesWithReportBuiltFromIt() throws Exception {
        Incident incident = newIncident();
        model.enqueue(finalAnswer(validDraft(incident.id())));

        Investigation started = orchestrator.start(incident);

        assertThat(started.status()).isEqualTo(InvestigationStatus.PENDING);
        assertThat(started.windowStart()).isEqualTo(DETECTED_AT.minus(properties.window().lookBack()));
        assertThat(started.windowEnd()).isEqualTo(DETECTED_AT.plus(properties.window().lookAhead()));
        Evidence alert = evidenceRepository.find(alertId(incident.id())).orElseThrow();
        assertThat(alert.type()).isEqualTo(EvidenceType.ALERT);
        assertThat(alert.observedAt()).isEqualTo(DETECTED_AT);

        InvestigationReport report = awaitReport(incident.id());
        assertThat(investigations.find(incident.id()).orElseThrow().status()).isEqualTo(InvestigationStatus.COMPLETED);
        assertThat(report.status()).isEqualTo(InvestigationStatus.COMPLETED);
        assertThat(report.summary()).isEqualTo("Checkout errors rose after payment-provider timeouts.");
        assertThat(report.timeline().entries()).extracting(TimelineEntry::at).containsExactly(DETECTED_AT);
        assertThat(report.actions()).extracting(RecommendedAction::order).containsExactly(1, 2);
        assertThat(report.evidence()).extracting(e -> e.id()).containsExactly(alertId(incident.id()));
        assertThat(incidents.find(incident.id()).orElseThrow().status()).isEqualTo(IncidentStatus.REPORTED);
        assertThat(contexts.find(incident.id())).isEmpty();
    }

    @Test
    void validDraftWithBudgetExhaustedIsInconclusiveWithBudgetQuestion() throws Exception {
        Incident incident = newIncident();
        model.enqueue(request -> {
            contexts.find(incident.id()).orElseThrow().budgetExhausted().set(true);
            return finalAnswer(validDraft(incident.id())).apply(request);
        });

        orchestrator.start(incident);

        InvestigationReport report = awaitReport(incident.id());
        assertThat(report.status()).isEqualTo(InvestigationStatus.INCONCLUSIVE);
        assertThat(report.summary()).isEqualTo("Checkout errors rose after payment-provider timeouts.");
        assertThat(report.openQuestions()).containsExactly("Is the provider degraded?",
                InvestigationOrchestrator.budgetQuestion(properties.budget().maxToolCalls()));
    }

    @Test
    void unparseableDraftGivesInconclusiveFallbackListingTheErrors() throws Exception {
        Incident incident = newIncident();
        // The same invalid draft twice: the first answer and the correction (Req 4.3, 4.4).
        String unparseable = "{\"incidentId\": \"" + incident.id() + "\", \"surprise\": 1}";
        model.enqueue(finalAnswer(unparseable), finalAnswer(unparseable));

        orchestrator.start(incident);

        InvestigationReport report = awaitReport(incident.id());
        assertThat(report.status()).isEqualTo(InvestigationStatus.INCONCLUSIVE);
        assertThat(report.summary()).startsWith("Investigation inconclusive: " + InvestigationOrchestrator.DRAFT_INVALID);
        // The reason, then every schema error: five missing fields and the unknown one.
        assertThat(report.openQuestions()).hasSize(7);
        assertThat(report.openQuestions().get(0)).isEqualTo(InvestigationOrchestrator.DRAFT_INVALID);
        assertThat(report.openQuestions().subList(1, 7)).allSatisfy(q -> assertThat(q).startsWith("$"))
                .anySatisfy(q -> assertThat(q).startsWith("$.surprise"));
        assertThat(report.hypotheses()).isEmpty();
        assertThat(report.timeline().entries()).singleElement()
                .satisfies(e -> assertThat(e.evidenceIds()).containsExactly(alertId(incident.id())));
        assertThat(report.actions()).extracting(RecommendedAction::type)
                .containsExactly(ActionType.INVESTIGATE, ActionType.ESCALATE);
        assertThat(incidents.find(incident.id()).orElseThrow().status()).isEqualTo(IncidentStatus.REPORTED);
    }

    @Test
    void draftCitingAnotherIncidentGivesInconclusiveFallbackNamingTheViolation() throws Exception {
        Incident incident = newIncident();
        String foreign = validDraft(incident.id()).replace("\"supportingEvidenceIds\": [\"" + alertId(incident.id()),
                "\"supportingEvidenceIds\": [\"ev-inc-000000000000-1");
        model.enqueue(finalAnswer(foreign), finalAnswer(foreign));

        orchestrator.start(incident);

        InvestigationReport report = awaitReport(incident.id());
        assertThat(model.requests()).hasSize(2);
        assertThat(report.status()).isEqualTo(InvestigationStatus.INCONCLUSIVE);
        assertThat(report.openQuestions()).containsExactly(InvestigationOrchestrator.DRAFT_INVALID,
                "$.hypotheses[0].supportingEvidenceIds[0]: is not an evidence ID of incident " + incident.id()
                        + " (expected ev-" + incident.id() + "-<n>)");
    }

    @Test
    void roundTripLimitGivesInconclusiveFallback() throws Exception {
        Incident incident = newIncident();
        for (int i = 0; i < properties.budget().maxToolCalls() + 3; i++) {
            model.enqueue(toolCall("getMetric", METRIC_ARGS));
        }

        orchestrator.start(incident);

        InvestigationReport report = awaitReport(incident.id());
        assertThat(report.status()).isEqualTo(InvestigationStatus.INCONCLUSIVE);
        assertThat(report.openQuestions()).first().isEqualTo(InvestigationOrchestrator.ROUND_TRIP_LIMIT);
        // The tool calls stored METRIC evidence; the fallback timeline cites all of it.
        assertThat(report.timeline().entries()).hasSize(evidenceRepository.findByIncident(incident.id()).size());
    }

    @Test
    void modelErrorGivesFailedFallback() throws Exception {
        Incident incident = newIncident();
        model.enqueue(request -> {
            throw new IllegalStateException("provider unreachable");
        });

        orchestrator.start(incident);

        InvestigationReport report = awaitReport(incident.id());
        assertThat(investigations.find(incident.id()).orElseThrow().status()).isEqualTo(InvestigationStatus.FAILED);
        assertThat(report.status()).isEqualTo(InvestigationStatus.FAILED);
        assertThat(report.summary()).startsWith("Investigation failed: " + InvestigationOrchestrator.LLM_FAILED);
        assertThat(report.summary()).doesNotContain("provider unreachable");
    }

    @Test
    void timeoutGivesInconclusiveFallbackAndTheLateResultIsDiscarded() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        AtomicReference<Thread> agentThread = new AtomicReference<>();
        InvestigatorAgent blockingAgent = new StubAgent() {
            @Override
            public String investigate(String incidentId, String incidentJson, String windowStart, String windowEnd,
                    String alertEvidenceId, String reportSchema) {
                agentThread.set(Thread.currentThread());
                try {
                    // Ignores interruption, like a blocking HTTP call that does not react to it.
                    while (true) {
                        try {
                            release.await();
                            break;
                        } catch (InterruptedException ignored) {
                            // keep waiting
                        }
                    }
                    return validDraft(incidentId);
                } finally {
                    returned.countDown();
                }
            }
        };
        InvestigatorProperties shortTimeout = new InvestigatorProperties(properties.displayTimezone(),
                properties.window(),
                new InvestigatorProperties.Budget(properties.budget().maxToolCalls(), Duration.ofMillis(300)),
                properties.tools(), properties.agent(), properties.services(), properties.mock());
        InvestigationOrchestrator shortOrchestrator = new InvestigationOrchestrator(investigations, incidents, reports,
                evidenceRepository, evidenceService, contexts, blockingAgent, promptInputs, parser, validator,
                assembler, fallback, publisher, shortTimeout, clock, new NoOpAuditService());
        try {
            Incident incident = newIncident();
            long started = System.nanoTime();
            shortOrchestrator.start(incident);

            InvestigationReport report = awaitReport(incident.id());
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
            assertThat(report.status()).isEqualTo(InvestigationStatus.INCONCLUSIVE);
            assertThat(report.openQuestions()).first().isEqualTo("timed out after 300ms");
            assertThat(agentThread.get().isVirtual()).isTrue();
            assertThat(contexts.find(incident.id())).isEmpty();

            release.countDown();
            assertThat(returned.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(50);
            assertThat(investigations.find(incident.id()).orElseThrow().status())
                    .isEqualTo(InvestigationStatus.INCONCLUSIVE);
            assertThat(reports.find(incident.id()).orElseThrow()).isEqualTo(report);
        } finally {
            release.countDown();
            shortOrchestrator.shutdown();
        }
    }

    private InvestigationReport awaitReport(String incidentId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            var report = reports.find(incidentId);
            if (report.isPresent()) {
                return report.get();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("no report for " + incidentId + " within 10 s");
    }

    private Incident newIncident() {
        byte[] bytes = new byte[6];
        ThreadLocalRandom.current().nextBytes(bytes);
        Incident incident = new Incident("inc-" + HexFormat.of().formatHex(bytes), "HighErrorRate on checkout-api",
                Severity.CRITICAL, "checkout-api", DETECTED_AT, "HighErrorRate", Map.of("env", "prod"),
                List.of(), "f".repeat(64), IncidentStatus.OPEN);
        incidents.save(incident);
        return incident;
    }

    private static String alertId(String incidentId) {
        return "ev-" + incidentId + "-1";
    }

    private static String validDraft(String incidentId) {
        String alert = alertId(incidentId);
        return """
                {
                  "incidentId": "%1$s",
                  "summary": "Checkout errors rose after payment-provider timeouts.",
                  "timeline": [
                    { "at": "2026-11-23T14:40:00Z", "description": "Alert fired", "evidenceIds": ["%2$s"] }
                  ],
                  "hypotheses": [
                    { "statement": "Provider timeouts", "confidence": "MEDIUM", "supportingEvidenceIds": ["%2$s"],
                      "contradictingEvidenceIds": [], "establishedCause": false }
                  ],
                  "actions": [
                    { "order": 2, "type": "ESCALATE", "description": "Page payments", "requiresApproval": false,
                      "evidenceIds": ["%2$s"] },
                    { "order": 1, "type": "INVESTIGATE", "description": "Check provider status",
                      "requiresApproval": false, "evidenceIds": ["%2$s"] }
                  ],
                  "openQuestions": ["Is the provider degraded?"]
                }
                """.formatted(incidentId, alert);
    }

    /** An {@link InvestigatorAgent} without chat memory. */
    private abstract static class StubAgent implements InvestigatorAgent {

        @Override
        public String correct(String incidentId, String errors) {
            return ScriptedChatModel.DEFAULT_FINAL_ANSWER;
        }

        @Override
        public ChatMemory getChatMemory(Object memoryId) {
            return null;
        }

        @Override
        public boolean evictChatMemory(Object memoryId) {
            return false;
        }
    }
}
