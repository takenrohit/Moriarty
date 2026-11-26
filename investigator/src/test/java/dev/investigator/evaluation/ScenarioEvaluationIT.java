package dev.investigator.evaluation;

import static dev.investigator.testsupport.ScriptedChatModel.finalAnswer;
import static dev.investigator.testsupport.ScriptedChatModel.toolCall;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.investigator.domain.Evidence;
import dev.investigator.domain.InvestigationReport;
import dev.investigator.domain.InvestigationStatus;
import dev.investigator.integration.mock.ScenarioKind;
import dev.investigator.report.ReportValidator;
import dev.investigator.store.EvidenceRepository;
import dev.investigator.testsupport.DemoScenarioScript;
import dev.investigator.testsupport.InvestigatorSpringBootTest;
import dev.investigator.testsupport.ScenarioEvaluator;
import dev.investigator.testsupport.ScriptedChatModel;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Scenario_Library evaluation with the Scripted_Chat_Model (reliability Req 10.1 to 10.4, CP-75): for
 * each scenario an alert with its {@code scenario} label goes in through the API, a plausible script
 * reads the runtime Evidence_IDs and answers with a draft naming the scenario's cause, and the stored
 * report must pass {@link ScenarioEvaluator#evaluate(InvestigationReport, java.util.Collection, String,
 * ScenarioKind.ExpectedCause)} for that scenario's expected cause. Each test also checks that the tool
 * results show the scenario's own evidence, so the mock data distinguishes the scenarios.
 */
@InvestigatorSpringBootTest
@AutoConfigureMockMvc
class ScenarioEvaluationIT {

    private static final Duration REPORT_LIMIT = Duration.ofSeconds(30);
    private static final String WS = DemoScenarioScript.WINDOW_START;
    private static final String WE = DemoScenarioScript.WINDOW_END;

    private static final String CHECKOUT_ERRORS = DemoScenarioScript.ERROR_RATIO_PROMQL;
    private static final String GATEWAY_P99 = DemoScenarioScript.GATEWAY_P99_PROMQL;
    private static final String CHECKOUT_P99 = "histogram_quantile(0.99, sum(rate("
            + "http_request_duration_seconds_bucket{service=\\\"checkout-api\\\"}[5m])) by (le))";
    private static final String CHECKOUT_CPU_THROTTLING =
            "sum(rate(container_cpu_cfs_throttled_seconds_total{service=\\\"checkout-api\\\"}[5m]))";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ScriptedChatModel model;
    @Autowired EvidenceRepository evidence;
    @Autowired ReportValidator validator;

    @BeforeEach
    void resetModel() {
        model.reset();
    }

    /** An alert on checkout-api at the demo time carrying {@code scenario = label}. */
    static String alert(String alertName, String label) {
        return """
                {
                  "alert": "%s",
                  "service": "checkout-api",
                  "severity": "critical",
                  "detectedAt": "2026-11-23T14:40:00Z",
                  "labels": { "environment": "production", "region": "ap-south-1", "scenario": "%s" },
                  "dashboardLinks": ["http://localhost:3000/d/checkout"]
                }
                """.formatted(alertName, label);
    }

    /** The label used for each scenario; mixed spellings exercise the case-insensitive selection. */
    static String label(ScenarioKind kind) {
        return switch (kind) {
            case PAYMENT_GATEWAY -> "no-such-scenario";
            case BAD_DEPLOY -> "bad-deploy";
            case DB_POOL_EXHAUSTION -> "db_pool_exhaustion";
            case NOISY_NEIGHBOUR -> "Noisy-Neighbour";
        };
    }

    static String alertName(ScenarioKind kind) {
        return kind == ScenarioKind.NOISY_NEIGHBOUR ? "HighLatency" : "HighErrorRate";
    }

    @Test
    void unknownLabelServesTheDefaultScenarioAndTheDemoReportPasses() throws Exception {
        DemoScenarioScript.enqueue(model);

        Run run = investigate(ScenarioKind.PAYMENT_GATEWAY);

        assertThat(run.results().get(2).text()).contains("PaymentGatewayTimeoutException");
        assertThat(new ScenarioEvaluator().evaluate(run.report(), run.stored(), run.markdown())).isEmpty();
        run.assertPasses(ScenarioKind.PAYMENT_GATEWAY);
    }

    @Test
    void badDeployIsEstablishedAsThePrimaryCause() throws Exception {
        model.enqueue(
                metric("checkout-api", CHECKOUT_ERRORS),
                toolCall("getRecentChanges", "{\"service\": \"checkout-api\", \"lookbackHours\": 2}"),
                logs("NullPointerException"),
                traces(),
                metric("payment-gateway", GATEWAY_P99),
                request -> {
                    Ids ids = Ids.from(request);
                    String errors = ids.get(0), deploy = ids.get(1), npe = ids.get(2), traces = ids.get(3),
                            gateway = ids.get(4);
                    return finalAnswer(draft(ids, "checkout-api v2.14.3 broke the payment client [%s][%s]."
                                    .formatted(deploy, npe),
                            List.of(timeline("2026-11-23T14:26:00Z", "checkout-api v2.14.3 deployed", deploy),
                                    timeline("2026-11-23T14:28:00Z",
                                            "NullPointerException at PaymentClient.authorize and 5xx begin", errors,
                                            npe),
                                    timeline("2026-11-23T14:40:00Z", "alert fires", ids.alert())),
                            List.of(hypothesis("The checkout-api v2.14.3 deployment introduced a NullPointerException"
                                            + " in PaymentClient.authorize, causing the 5xx errors", "HIGH", true,
                                            List.of(deploy, npe, traces, errors), List.of()),
                                    hypothesis("Payment-gateway degradation caused the errors", "LOW", false,
                                            List.of(errors), List.of(gateway, traces))),
                            List.of(action(1, "ROLLBACK", "Roll back checkout-api to v2.14.2.", true, deploy, npe),
                                    action(2, "INVESTIGATE", "Review the PaymentClient and RetryPolicy changes.",
                                            false, deploy)))).apply(request);
                });

        Run run = investigate(ScenarioKind.BAD_DEPLOY);

        assertThat(run.results().get(1).text()).contains("v2.14.3", "PaymentClient.java", "RetryPolicy.java");
        assertThat(run.results().get(2).text()).contains("NullPointerException at PaymentClient.authorize");
        assertThat(run.results().get(3).text()).contains("PaymentClient.authorize")
                .doesNotContain("payment-provider");
        assertThat(run.results().get(4).text()).as("payment-gateway p99 is healthy")
                .contains("\"max\":180.0", "\"changePointAt\":null");
        run.assertPasses(ScenarioKind.BAD_DEPLOY);
    }

    @Test
    void dbPoolExhaustionIsNotBlamedOnADeploy() throws Exception {
        model.enqueue(
                metric("checkout-api", CHECKOUT_ERRORS),
                toolCall("getRecentChanges", "{\"service\": \"checkout-api\", \"lookbackHours\": 24}"),
                logs("HikariPool-1"),
                traces(),
                metric("payment-gateway", GATEWAY_P99),
                toolCall("findRunbook", "{\"service\": \"checkout-api\", \"symptom\": \"connection pool exhausted\"}"),
                request -> {
                    Ids ids = Ids.from(request);
                    String errors = ids.get(0), hikari = ids.get(2), traces = ids.get(3), gateway = ids.get(4),
                            runbook = ids.get(5);
                    return finalAnswer(draft(ids, "checkout-api ran out of database connections [%s][%s]."
                                    .formatted(hikari, traces),
                            List.of(timeline("2026-11-23T14:35:00Z", "HikariPool-1 connection timeouts begin", hikari,
                                            errors),
                                    timeline("2026-11-23T14:40:00Z", "alert fires", ids.alert())),
                            List.of(hypothesis("checkout-api exhausted its database connection pool (HikariPool-1"
                                            + " timeouts waiting for postgres connections)", "HIGH", true,
                                            List.of(hikari, traces, errors), List.of()),
                                    hypothesis("Payment-gateway degradation caused the errors", "LOW", false,
                                            List.of(errors), List.of(gateway, traces))),
                            List.of(action(1, "INVESTIGATE", "Find long-running queries holding connections.", false,
                                            traces, runbook),
                                    action(2, "CONFIG_CHANGE", "Raise the pool size within the database limit.",
                                            true, runbook, hikari)))).apply(request);
                });

        Run run = investigate(ScenarioKind.DB_POOL_EXHAUSTION);

        assertThat(ScriptedChatModel.evidenceIds(run.results().get(1))).as("deployments in the last 24 h").isEmpty();
        assertThat(run.results().get(2).text()).contains("HikariPool-1 - Connection is not available");
        assertThat(run.results().get(3).text()).contains("postgres");
        run.assertPasses(ScenarioKind.DB_POOL_EXHAUSTION);
    }

    @Test
    void noisyNeighbourIsNotBlamedOnADeployOrADependency() throws Exception {
        model.enqueue(
                metric("checkout-api", CHECKOUT_P99),
                metric("checkout-api", CHECKOUT_CPU_THROTTLING),
                toolCall("getRecentChanges", "{\"service\": \"checkout-api\", \"lookbackHours\": 24}"),
                logs("GC overhead"),
                traces(),
                metric("payment-gateway", GATEWAY_P99),
                request -> {
                    Ids ids = Ids.from(request);
                    String latency = ids.get(0), cpu = ids.get(1), gc = ids.get(3), traces = ids.get(4),
                            gateway = ids.get(5);
                    return finalAnswer(draft(ids, "checkout-api p99 latency rose while its CPU was throttled [%s][%s]."
                                    .formatted(latency, cpu),
                            List.of(timeline("2026-11-23T14:34:00Z", "checkout-api CPU throttling and p99 rise", cpu,
                                            latency, gc),
                                    timeline("2026-11-23T14:40:00Z", "alert fires", ids.alert())),
                            List.of(hypothesis("checkout-api is CPU-throttled by a noisy neighbour on its node,"
                                            + " raising p99 latency", "MEDIUM", false,
                                            List.of(cpu, latency, gc, traces), List.of()),
                                    hypothesis("A slow payment-gateway dependency caused the latency", "LOW", false,
                                            List.of(latency), List.of(gateway, traces))),
                            List.of(action(1, "INVESTIGATE", "Check node CPU usage of co-located workloads.", false,
                                            cpu),
                                    action(2, "TRAFFIC_SHIFT", "Move checkout-api pods to other nodes.", true, cpu,
                                            latency)))).apply(request);
                });

        Run run = investigate(ScenarioKind.NOISY_NEIGHBOUR);

        assertThat(run.results().get(1).text()).contains("0.97");
        assertThat(ScriptedChatModel.evidenceIds(run.results().get(2))).as("deployments in the last 24 h").isEmpty();
        assertThat(run.results().get(3).text()).contains("GC overhead");
        assertThat(run.results().get(5).text()).as("payment-gateway p99 is healthy")
                .contains("\"max\":180.0", "\"changePointAt\":null");
        run.assertPasses(ScenarioKind.NOISY_NEIGHBOUR);
    }

    // ---- script steps ----

    private static Function<ChatRequest, ChatResponse> metric(String service, String promql) {
        return toolCall("getMetric", """
                {"service": "%s", "promql": "%s", "start": "%s", "end": "%s"}
                """.formatted(service, promql, WS, WE));
    }

    private static Function<ChatRequest, ChatResponse> logs(String text) {
        return toolCall("searchLogs", """
                {"service": "checkout-api", "query": "{service=\\"checkout-api\\"} |= \\"%s\\"", "start": "%s", "end": "%s"}
                """.formatted(text, WS, WE));
    }

    private static Function<ChatRequest, ChatResponse> traces() {
        return toolCall("findProblemTraces", """
                {"service": "checkout-api", "start": "%s", "end": "%s"}
                """.formatted(WS, WE));
    }

    /** The first Evidence_ID of each tool result, in call order (null for a result without one). */
    private record Ids(String incidentId, List<String> perCall) {

        static Ids from(ChatRequest request) {
            List<String> perCall = new ArrayList<>();
            String incidentId = null;
            for (ToolExecutionResultMessage result : ScriptedChatModel.toolResults(request)) {
                List<String> found = ScriptedChatModel.evidenceIds(result);
                String id = found.isEmpty() ? null : found.get(0);
                perCall.add(id);
                if (incidentId == null && id != null) {
                    incidentId = id.substring("ev-".length(), id.lastIndexOf('-'));
                }
            }
            if (incidentId == null) {
                throw new IllegalStateException("no tool result names an evidence ID");
            }
            return new Ids(incidentId, perCall);
        }

        String get(int call) {
            String id = perCall.get(call);
            if (id == null) {
                throw new IllegalStateException("tool result " + (call + 1) + " has no evidence ID");
            }
            return id;
        }

        String alert() {
            return "ev-" + incidentId + "-1";
        }
    }

    // ---- draft JSON ----

    private static String draft(Ids ids, String summary, List<String> timeline, List<String> hypotheses,
            List<String> actions) {
        return """
                {"incidentId": "%s", "summary": "%s", "timeline": [%s], "hypotheses": [%s], "actions": [%s],
                 "openQuestions": ["What else changed in the environment?"]}
                """.formatted(ids.incidentId(), summary, String.join(",", timeline), String.join(",", hypotheses),
                String.join(",", actions));
    }

    private static String timeline(String at, String description, String... ids) {
        return """
                {"at": "%s", "description": "%s", "evidenceIds": %s}""".formatted(at, description, json(List.of(ids)));
    }

    private static String hypothesis(String statement, String confidence, boolean established, List<String> supporting,
            List<String> contradicting) {
        return """
                {"statement": "%s", "confidence": "%s", "supportingEvidenceIds": %s,
                 "contradictingEvidenceIds": %s, "establishedCause": %s}"""
                .formatted(statement, confidence, json(supporting), json(contradicting), established);
    }

    private static String action(int order, String type, String description, boolean approval, String... ids) {
        return """
                {"order": %d, "type": "%s", "description": "%s", "requiresApproval": %s, "evidenceIds": %s}"""
                .formatted(order, type, description, approval, json(List.of(ids)));
    }

    private static String json(List<String> ids) {
        return ids.stream().map(id -> "\"" + id + "\"").reduce((a, b) -> a + ", " + b).map(s -> "[" + s + "]")
                .orElse("[]");
    }

    // ---- running and checking ----

    private Run investigate(ScenarioKind kind) throws Exception {
        long posted = System.nanoTime();
        String accepted = mvc.perform(post("/api/v1/alerts").contentType(MediaType.APPLICATION_JSON)
                        .content(alert(alertName(kind), label(kind)).getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String incidentId = mapper.readTree(accepted).get("incidentId").asText();
        InvestigationReport report = mapper.readValue(awaitJsonReport(incidentId, posted), InvestigationReport.class);
        String markdown = mvc.perform(get("/api/v1/incidents/{id}/report.md", incidentId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String> stored = evidence.findByIncident(incidentId).stream().map(Evidence::id).toList();
        List<ToolExecutionResultMessage> results =
                ScriptedChatModel.toolResults(model.requests().get(model.requests().size() - 1));
        assertThat(report.status()).as("report status, summary: %s", report.summary())
                .isEqualTo(InvestigationStatus.COMPLETED);
        assertThat(model.remainingSteps()).isZero();
        return new Run(report, markdown, stored, results, validator);
    }

    private record Run(InvestigationReport report, String markdown, List<String> stored,
            List<ToolExecutionResultMessage> results, ReportValidator validator) {

        void assertPasses(ScenarioKind kind) {
            assertThat(new ScenarioEvaluator().evaluate(report, stored, markdown, kind.expectedCause()))
                    .as("failed Scenario_Evaluator checks for %s; Markdown:%n%s", kind, markdown)
                    .isEmpty();
            assertThat(validator.validateReport(report, Set.copyOf(stored)).violations()).isEmpty();
        }
    }

    private String awaitJsonReport(String incidentId, long posted) throws Exception {
        long deadline = posted + REPORT_LIMIT.toNanos();
        while (System.nanoTime() < deadline) {
            MvcResult result = mvc.perform(get("/api/v1/incidents/{id}/report", incidentId)).andReturn();
            int code = result.getResponse().getStatus();
            if (code == 200) {
                return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            }
            assertThat(code).isEqualTo(202);
            Thread.sleep(20);
        }
        throw new AssertionError("no report for " + incidentId + " within " + REPORT_LIMIT);
    }
}
