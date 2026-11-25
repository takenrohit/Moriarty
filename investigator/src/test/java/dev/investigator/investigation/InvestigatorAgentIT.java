package dev.investigator.investigation;

import static dev.investigator.testsupport.ScriptedChatModel.call;
import static dev.investigator.testsupport.ScriptedChatModel.evidenceIds;
import static dev.investigator.testsupport.ScriptedChatModel.finalAnswer;
import static dev.investigator.testsupport.ScriptedChatModel.lastToolResult;
import static dev.investigator.testsupport.ScriptedChatModel.toolCall;
import static dev.investigator.testsupport.ScriptedChatModel.toolCalls;
import static org.assertj.core.api.Assertions.assertThat;

import dev.investigator.config.InvestigatorProperties;
import dev.investigator.domain.ActionType;
import dev.investigator.domain.Evidence;
import dev.investigator.domain.EvidenceRef;
import dev.investigator.domain.EvidenceType;
import dev.investigator.domain.Incident;
import dev.investigator.domain.IncidentStatus;
import dev.investigator.domain.Investigation;
import dev.investigator.domain.InvestigationReport;
import dev.investigator.domain.InvestigationStatus;
import dev.investigator.domain.RecommendedAction;
import dev.investigator.domain.Severity;
import dev.investigator.store.EvidenceRepository;
import dev.investigator.store.IncidentRepository;
import dev.investigator.store.InvestigationRepository;
import dev.investigator.store.ReportRepository;
import dev.investigator.testsupport.InvestigatorSpringBootTest;
import dev.investigator.testsupport.ScriptedChatModel;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Stubbed-model agent test (NFR-42, plan 1.12): each investigation runs through the real
 * Investigation_Orchestrator, Investigator_Agent AI service, Observability_Tools, Tool_Guard, mock
 * clients and Evidence_Service, with the Scripted_Chat_Model in place of the LLM. Drafts go through the
 * full parser and validator, so they cite the Evidence_IDs the scripted steps read from the tool results
 * (Req 13.1, 13.4 to 13.6, 14.4, 14.5, 19.2 to 19.4).
 */
@InvestigatorSpringBootTest
class InvestigatorAgentIT {

    private static final Instant DETECTED_AT = Instant.parse("2026-11-23T14:40:00Z");
    private static final String WINDOW_START = "2026-11-23T13:40:00Z";
    private static final String WINDOW_END = "2026-11-23T14:50:00Z";
    private static final String METRIC_ARGS = """
            {"service": "checkout-api",
             "promql": "sum(rate(http_requests_total{service=\\"checkout-api\\",status=~\\"5..\\"}[5m])) / sum(rate(http_requests_total{service=\\"checkout-api\\"}[5m]))",
             "start": "%s", "end": "%s"}
            """.formatted(WINDOW_START, WINDOW_END);
    private static final String CHANGES_ARGS = "{\"service\": \"checkout-api\", \"lookbackHours\": 2}";
    private static final String LOGS_ARGS = """
            {"service": "checkout-api",
             "query": "{service=\\"checkout-api\\"} |= \\"PaymentGatewayTimeoutException\\"",
             "start": "%s", "end": "%s"}
            """.formatted(WINDOW_START, WINDOW_END);
    private static final String TRACES_ARGS =
            "{\"service\": \"checkout-api\", \"start\": \"" + WINDOW_START + "\", \"end\": \"" + WINDOW_END + "\"}";
    private static final String RUNBOOK_ARGS = "{\"service\": \"payment-gateway\", \"symptom\": \"timeouts\"}";
    private static final String SUMMARY = "Checkout errors rose after payment-gateway timeouts.";

    @Autowired InvestigationOrchestrator orchestrator;
    @Autowired ScriptedChatModel model;
    @Autowired PromptInputs promptInputs;
    @Autowired IncidentRepository incidents;
    @Autowired InvestigationRepository investigations;
    @Autowired ReportRepository reports;
    @Autowired EvidenceRepository evidence;
    @Autowired InvestigatorProperties properties;

    @BeforeEach
    void resetModel() {
        model.reset();
    }

    /** (1) Every scripted tool call passes the guard, reaches a mock client and stores typed Evidence. */
    @Test
    void scriptedToolCallsPassThroughGuardMockClientsAndEvidenceService() throws Exception {
        Incident incident = newIncident();
        AtomicReference<ChatRequest> last = new AtomicReference<>();
        model.enqueue(
                toolCalls(call("getMetric", METRIC_ARGS), call("getRecentChanges", CHANGES_ARGS)),
                toolCalls(call("searchLogs", LOGS_ARGS), call("findProblemTraces", TRACES_ARGS)),
                toolCall("findRunbook", RUNBOOK_ARGS),
                request -> {
                    last.set(request);
                    return finalAnswer(fullDraft(incident.id(), request)).apply(request);
                });

        orchestrator.start(incident);
        awaitReport(incident.id());

        ChatRequest finalRequest = last.get();
        assertThat(ScriptedChatModel.toolResults(finalRequest)).extracting(ToolExecutionResultMessage::toolName)
                .containsExactly("getMetric", "getRecentChanges", "searchLogs", "findProblemTraces", "findRunbook");
        assertToolStored(incident.id(), finalRequest, "getMetric", EvidenceType.METRIC);
        assertToolStored(incident.id(), finalRequest, "getRecentChanges", EvidenceType.DEPLOYMENT);
        assertToolStored(incident.id(), finalRequest, "searchLogs", EvidenceType.LOG);
        assertToolStored(incident.id(), finalRequest, "findProblemTraces", EvidenceType.TRACE);
        assertToolStored(incident.id(), finalRequest, "findRunbook", EvidenceType.RUNBOOK);

        // The guard counted every invocation; the store holds the ALERT plus what the tools returned.
        assertThat(investigations.find(incident.id()).orElseThrow().toolCallsUsed()).isEqualTo(5);
        List<String> expectedIds = new ArrayList<>();
        expectedIds.add(alertId(incident.id()));
        expectedIds.addAll(evidenceIds(finalRequest));
        assertThat(evidence.findByIncident(incident.id())).extracting(Evidence::id)
                .containsExactlyInAnyOrderElementsOf(expectedIds);
        assertThat(evidence.findByIncident(incident.id())).extracting(Evidence::type)
                .contains(EvidenceType.ALERT, EvidenceType.METRIC, EvidenceType.DEPLOYMENT, EvidenceType.LOG,
                        EvidenceType.TRACE, EvidenceType.RUNBOOK);
        assertThat(model.remainingSteps()).isZero();
    }

    /** (2) The first request carries the system prompt, the five tools and the user message inputs. */
    @Test
    void firstRequestContainsIncidentJsonWindowAlertIdAndSchema() throws Exception {
        Incident incident = newIncident();
        model.enqueue(finalAnswer(alertOnlyDraft(incident.id())));

        orchestrator.start(incident);
        awaitReport(incident.id());

        assertThat(model.requests()).hasSize(1);
        ChatRequest first = model.requests().get(0);
        assertThat(first.messages().get(0)).isInstanceOfSatisfying(SystemMessage.class,
                m -> assertThat(m.text()).contains("You cannot change systems"));
        Incident investigating = new Incident(incident.id(), incident.title(), incident.severity(),
                incident.affectedService(), incident.detectedAt(), incident.alertName(), incident.labels(),
                incident.dashboardLinks(), incident.fingerprint(), IncidentStatus.INVESTIGATING);
        assertThat(first.messages().get(1)).isInstanceOfSatisfying(UserMessage.class,
                m -> assertThat(m.singleText())
                        .contains(promptInputs.incidentJson(investigating))
                        .contains("from " + WINDOW_START + " to " + WINDOW_END)
                        .contains("ALERT evidence ID: " + alertId(incident.id()))
                        .contains(promptInputs.reportSchema())
                        .doesNotContain("{{"));
        assertThat(first.toolSpecifications()).extracting(ToolSpecification::name)
                .containsExactlyInAnyOrder("getMetric", "searchLogs", "findProblemTraces", "getRecentChanges",
                        "findRunbook");
    }

    /** (3) A valid draft citing runtime Evidence_IDs ends COMPLETED with the report built from it. */
    @Test
    void validDraftEndsCompletedWithAStoredReport() throws Exception {
        Incident incident = newIncident();
        model.enqueue(
                toolCalls(call("getMetric", METRIC_ARGS), call("getRecentChanges", CHANGES_ARGS)),
                toolCalls(call("searchLogs", LOGS_ARGS), call("findProblemTraces", TRACES_ARGS)),
                toolCall("findRunbook", RUNBOOK_ARGS),
                request -> finalAnswer(fullDraft(incident.id(), request)).apply(request));

        orchestrator.start(incident);

        InvestigationReport report = awaitReport(incident.id());
        Investigation investigation = investigations.find(incident.id()).orElseThrow();
        assertThat(investigation.status()).isEqualTo(InvestigationStatus.COMPLETED);
        assertThat(report.status()).isEqualTo(InvestigationStatus.COMPLETED);
        assertThat(report.summary()).isEqualTo(SUMMARY);
        assertThat(report.hypotheses()).hasSize(2);
        assertThat(report.actions()).extracting(RecommendedAction::type)
                .containsExactly(ActionType.INVESTIGATE, ActionType.ROLLBACK);
        assertThat(report.actions()).extracting(RecommendedAction::requiresApproval).containsExactly(false, true);
        assertThat(report.openQuestions()).containsExactly("Is payment-provider degraded?");
        assertThat(report.evidence()).extracting(EvidenceRef::id)
                .containsExactlyInAnyOrderElementsOf(evidence.findByIncident(incident.id()).stream()
                        .map(Evidence::id).toList());
        assertThat(incidents.find(incident.id()).orElseThrow().status()).isEqualTo(IncidentStatus.REPORTED);
    }

    /** (4) Calls past the Tool_Call_Budget are refused; the valid draft that follows ends INCONCLUSIVE. */
    @Test
    void budgetOverrunEndsInconclusive() throws Exception {
        int budget = properties.budget().maxToolCalls();
        Incident incident = newIncident();
        for (int i = 0; i <= budget; i++) {
            model.enqueue(toolCall("getMetric", METRIC_ARGS));
        }
        AtomicReference<String> refusal = new AtomicReference<>();
        model.enqueue(request -> {
            refusal.set(lastToolResult(request).map(ToolExecutionResultMessage::text).orElse(""));
            List<String> metrics = evidenceIds(request, "getMetric");
            return finalAnswer(metricDraft(incident.id(), metrics.get(0))).apply(request);
        });

        orchestrator.start(incident);

        InvestigationReport report = awaitReport(incident.id());
        assertThat(refusal.get()).contains("tool-call budget is used up").doesNotContain("evidenceId");
        assertThat(report.status()).isEqualTo(InvestigationStatus.INCONCLUSIVE);
        assertThat(report.summary()).isEqualTo(SUMMARY);
        assertThat(report.openQuestions()).containsExactly("Is payment-provider degraded?",
                InvestigationOrchestrator.budgetQuestion(budget));
        Investigation investigation = investigations.find(incident.id()).orElseThrow();
        assertThat(investigation.status()).isEqualTo(InvestigationStatus.INCONCLUSIVE);
        assertThat(investigation.toolCallsUsed()).isEqualTo(budget + 1);
        assertThat(evidence.findByIncident(incident.id()))
                .filteredOn(e -> e.type() == EvidenceType.METRIC).hasSize(budget);
    }

    /** (5) A model that never stops calling tools hits the round-trip limit and gets the fallback report. */
    @Test
    void endlessToolRequestsHitTheRoundTripLimitAndEndInconclusive() throws Exception {
        int limit = properties.budget().maxToolCalls() + 2;
        Incident incident = newIncident();
        for (int i = 0; i < limit + 5; i++) {
            model.enqueue(toolCall("getMetric", METRIC_ARGS));
        }

        orchestrator.start(incident);

        InvestigationReport report = awaitReport(incident.id());
        // The agent stopped at the limit (the exact count depends on how LangChain4j counts the last
        // round trip) and never reached the end of the script.
        assertThat(model.requests()).hasSizeBetween(limit, limit + 1);
        assertThat(model.remainingSteps()).isPositive();
        assertThat(report.status()).isEqualTo(InvestigationStatus.INCONCLUSIVE);
        assertThat(investigations.find(incident.id()).orElseThrow().status())
                .isEqualTo(InvestigationStatus.INCONCLUSIVE);
        assertThat(report.summary()).startsWith("Investigation inconclusive: " + InvestigationOrchestrator.ROUND_TRIP_LIMIT);
        assertThat(report.openQuestions()).first().isEqualTo(InvestigationOrchestrator.ROUND_TRIP_LIMIT);
        assertThat(report.hypotheses()).isEmpty();
        assertThat(report.actions()).extracting(RecommendedAction::type)
                .containsExactly(ActionType.INVESTIGATE, ActionType.ESCALATE);
        assertThat(report.timeline().entries()).hasSize(evidence.findByIncident(incident.id()).size());
    }

    private void assertToolStored(String incidentId, ChatRequest request, String toolName, EvidenceType type) {
        List<String> ids = evidenceIds(request, toolName);
        assertThat(ids).as("evidence IDs returned by %s", toolName).isNotEmpty();
        for (String id : ids) {
            assertThat(evidence.find(id)).as("stored evidence %s", id).hasValueSatisfying(e -> {
                assertThat(e.type()).isEqualTo(type);
                assertThat(e.incidentId()).isEqualTo(incidentId);
            });
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

    /** A demo-style draft citing the Evidence_IDs the scripted steps read from the tool results. */
    private static String fullDraft(String incidentId, ChatRequest request) {
        String metric = evidenceIds(request, "getMetric").get(0);
        String deploy = evidenceIds(request, "getRecentChanges").get(0);
        String logs = evidenceIds(request, "searchLogs").get(0);
        String traces = evidenceIds(request, "findProblemTraces").get(0);
        String runbook = evidenceIds(request, "findRunbook").get(0);
        return """
                {
                  "incidentId": "%1$s",
                  "summary": "%8$s",
                  "timeline": [
                    { "at": "2026-11-23T14:26:00Z", "description": "checkout-api v2.14.3 deployed",
                      "evidenceIds": ["%3$s"] },
                    { "at": "2026-11-23T14:38:00Z", "description": "checkout-api 5xx rate starts rising",
                      "evidenceIds": ["%2$s", "%4$s"] },
                    { "at": "2026-11-23T14:40:00Z", "description": "HighErrorRate alert fires",
                      "evidenceIds": ["%7$s"] }
                  ],
                  "hypotheses": [
                    { "statement": "Payment-gateway timeouts caused by slow responses from payment-provider",
                      "confidence": "HIGH", "supportingEvidenceIds": ["%2$s", "%4$s", "%5$s"],
                      "contradictingEvidenceIds": [], "establishedCause": false },
                    { "statement": "The checkout-api v2.14.3 deploy caused the errors", "confidence": "LOW",
                      "supportingEvidenceIds": ["%3$s"], "contradictingEvidenceIds": ["%2$s"],
                      "establishedCause": false }
                  ],
                  "actions": [
                    { "order": 1, "type": "INVESTIGATE",
                      "description": "Follow the payment gateway degradation runbook.",
                      "requiresApproval": false, "evidenceIds": ["%6$s"] },
                    { "order": 2, "type": "ROLLBACK",
                      "description": "Rollback of checkout-api is not recommended on current evidence.",
                      "requiresApproval": true, "evidenceIds": ["%3$s"] }
                  ],
                  "openQuestions": ["Is payment-provider degraded?"]
                }
                """.formatted(incidentId, metric, deploy, logs, traces, runbook, alertId(incidentId), SUMMARY);
    }

    /** A valid draft citing the ALERT and one METRIC Evidence_ID. */
    private static String metricDraft(String incidentId, String metric) {
        return """
                {
                  "incidentId": "%1$s",
                  "summary": "%4$s",
                  "timeline": [
                    { "at": "2026-11-23T14:38:00Z", "description": "checkout-api 5xx rate starts rising",
                      "evidenceIds": ["%2$s"] },
                    { "at": "2026-11-23T14:40:00Z", "description": "HighErrorRate alert fires",
                      "evidenceIds": ["%3$s"] }
                  ],
                  "hypotheses": [
                    { "statement": "Payment-gateway timeouts", "confidence": "MEDIUM",
                      "supportingEvidenceIds": ["%2$s"], "contradictingEvidenceIds": [], "establishedCause": false }
                  ],
                  "actions": [
                    { "order": 1, "type": "ESCALATE", "description": "Page the payments team.",
                      "requiresApproval": false, "evidenceIds": ["%3$s"] }
                  ],
                  "openQuestions": ["Is payment-provider degraded?"]
                }
                """.formatted(incidentId, metric, alertId(incidentId), SUMMARY);
    }

    /** A valid draft citing only the ALERT Evidence_ID. */
    private static String alertOnlyDraft(String incidentId) {
        return metricDraft(incidentId, alertId(incidentId));
    }
}
