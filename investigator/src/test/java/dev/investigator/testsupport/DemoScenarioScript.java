package dev.investigator.testsupport;

import static dev.investigator.testsupport.ScriptedChatModel.finalAnswer;
import static dev.investigator.testsupport.ScriptedChatModel.toolCall;

import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * The demo investigation of {@code demo-scenario.md} as a Scripted_Chat_Model script (design "Agent and
 * evaluation tests").
 *
 * <p>The script calls one tool per round trip, in the order the demo finds its evidence: the
 * checkout-api 5xx ratio, recent checkout-api changes, the {@code PaymentGatewayTimeoutException} logs,
 * the payment-gateway p99 latency, the 5xx ratio between the deploy and the onset (flat), the payment
 * gateway runbook and the checkout-api problem traces. The final step reads the Evidence_IDs assigned at
 * runtime from the tool results and answers with the expected report draft citing them.
 *
 * <p>Times assume the demo alert ({@link #DEMO_ALERT}, detected 2026-11-23T14:40:00Z = 20:10 IST), whose
 * Investigation_Window is {@value #WINDOW_START} to {@value #WINDOW_END}.
 */
public final class DemoScenarioScript {

    /** The incoming alert of {@code demo-scenario.md}. */
    public static final String DEMO_ALERT = """
            {
              "alert": "HighErrorRate",
              "service": "checkout-api",
              "severity": "critical",
              "detectedAt": "2026-11-23T14:40:00Z",
              "labels": { "environment": "production", "region": "ap-south-1" },
              "dashboardLinks": ["http://localhost:3000/d/checkout"]
            }
            """;

    public static final String WINDOW_START = "2026-11-23T13:40:00Z";
    public static final String WINDOW_END = "2026-11-23T14:50:00Z";
    /** The checkout-api v2.14.3 deployment (19:56 IST). */
    public static final String DEPLOYED_AT = "2026-11-23T14:26:00Z";
    /** Just before the checkout-api error onset at 14:38 UTC (20:08 IST). */
    public static final String BEFORE_ONSET = "2026-11-23T14:37:00Z";

    // Queries are JSON-escaped for direct use in tool arguments.
    public static final String ERROR_RATIO_PROMQL = "sum(rate(http_requests_total{service=\\\"checkout-api\\\","
            + "status=~\\\"5..\\\"}[5m])) / sum(rate(http_requests_total{service=\\\"checkout-api\\\"}[5m]))";
    public static final String GATEWAY_P99_PROMQL = "histogram_quantile(0.99, sum(rate("
            + "http_request_duration_seconds_bucket{service=\\\"payment-gateway\\\"}[5m])) by (le))";
    public static final String TIMEOUT_LOGQL =
            "{service=\\\"checkout-api\\\"} |= \\\"PaymentGatewayTimeoutException\\\"";

    /** Tool names in call order. */
    public static final List<String> TOOL_SEQUENCE = List.of("getMetric", "getRecentChanges", "searchLogs",
            "getMetric", "getMetric", "findRunbook", "findProblemTraces");

    private DemoScenarioScript() {
    }

    /** Enqueues the whole script (tool steps and the final draft) on {@code model}. */
    public static void enqueue(ScriptedChatModel model) {
        steps().forEach(model::enqueue);
    }

    /** The scripted steps: one per tool in {@link #TOOL_SEQUENCE}, then the final draft. */
    public static List<Function<ChatRequest, ChatResponse>> steps() {
        return List.of(
                toolCall("getMetric", metricArgs("checkout-api", ERROR_RATIO_PROMQL, WINDOW_START, WINDOW_END)),
                toolCall("getRecentChanges", "{\"service\": \"checkout-api\", \"lookbackHours\": 2}"),
                toolCall("searchLogs", """
                        {"service": "checkout-api", "query": "%s", "start": "%s", "end": "%s"}
                        """.formatted(TIMEOUT_LOGQL, WINDOW_START, WINDOW_END)),
                toolCall("getMetric", metricArgs("payment-gateway", GATEWAY_P99_PROMQL, WINDOW_START, WINDOW_END)),
                toolCall("getMetric", metricArgs("checkout-api", ERROR_RATIO_PROMQL, DEPLOYED_AT, BEFORE_ONSET)),
                toolCall("findRunbook",
                        "{\"service\": \"payment-gateway\", \"symptom\": \"upstream timeouts from payment-provider\"}"),
                toolCall("findProblemTraces", """
                        {"service": "checkout-api", "start": "%s", "end": "%s"}
                        """.formatted(WINDOW_START, WINDOW_END)),
                request -> finalAnswer(expectedDraft(EvidenceIds.fromToolResults(request))).apply(request));
    }

    private static String metricArgs(String service, String promql, String start, String end) {
        return """
                {"service": "%s", "promql": "%s", "start": "%s", "end": "%s"}
                """.formatted(service, promql, start, end);
    }

    /** The Evidence_IDs the demo report cites, one per piece of evidence in {@code demo-scenario.md}. */
    public record EvidenceIds(String incidentId, String alert, String errorRatio, String deployment,
            String timeoutLogs, String gatewayLatency, String flatErrorRatio, String runbook, String traces) {

        public EvidenceIds {
            Objects.requireNonNull(incidentId, "incidentId");
            for (String id : new String[] {alert, errorRatio, deployment, timeoutLogs, gatewayLatency,
                    flatErrorRatio, runbook, traces}) {
                Objects.requireNonNull(id, "evidence ID");
            }
        }

        /** IDs {@code ev-<incidentId>-1} (ALERT) to {@code -8} in the order the script stores them. */
        public static EvidenceIds sequential(String incidentId) {
            String p = "ev-" + incidentId + "-";
            return new EvidenceIds(incidentId, p + 1, p + 2, p + 3, p + 4, p + 5, p + 6, p + 7, p + 8);
        }

        /**
         * Reads the IDs from the tool results of the final request.
         *
         * @throws IllegalStateException if the tool results do not follow {@link #TOOL_SEQUENCE} or a
         *     result names no Evidence_ID (for example an error result)
         */
        public static EvidenceIds fromToolResults(ChatRequest request) {
            List<ToolExecutionResultMessage> results = ScriptedChatModel.toolResults(request);
            List<String> names = results.stream().map(ToolExecutionResultMessage::toolName).toList();
            if (!names.equals(TOOL_SEQUENCE)) {
                throw new IllegalStateException("tool results " + names + " do not follow " + TOOL_SEQUENCE);
            }
            String[] ids = new String[results.size()];
            for (int i = 0; i < results.size(); i++) {
                List<String> found = ScriptedChatModel.evidenceIds(results.get(i));
                if (found.isEmpty()) {
                    throw new IllegalStateException(
                            "tool result " + (i + 1) + " (" + names.get(i) + ") has no evidence ID: " + results.get(i).text());
                }
                ids[i] = found.get(0);
            }
            String incidentId = ids[0].substring("ev-".length(), ids[0].lastIndexOf('-'));
            return new EvidenceIds(incidentId, "ev-" + incidentId + "-1", ids[0], ids[1], ids[2], ids[3], ids[4],
                    ids[5], ids[6]);
        }
    }

    /** The expected report draft of {@code demo-scenario.md}, citing {@code ids}. */
    public static String expectedDraft(EvidenceIds ids) {
        return """
                {
                  "incidentId": "%1$s",
                  "summary": "The checkout-api error rate increased from 0.2%% to 12%% beginning at 20:08 IST [%3$s]. Logs and traces consistently show timeouts calling the payment gateway [%5$s][%9$s].",
                  "timeline": [
                    { "at": "2026-11-23T14:26:00Z", "description": "checkout-api v2.14.3 deployed",
                      "evidenceIds": ["%4$s"] },
                    { "at": "2026-11-23T14:37:00Z", "description": "payment-gateway p99 latency rises to 5 s",
                      "evidenceIds": ["%6$s"] },
                    { "at": "2026-11-23T14:38:00Z",
                      "description": "checkout-api 5xx rate starts rising; PaymentGatewayTimeoutException appears",
                      "evidenceIds": ["%3$s", "%5$s"] },
                    { "at": "2026-11-23T14:40:00Z", "description": "HighErrorRate alert fires",
                      "evidenceIds": ["%2$s"] }
                  ],
                  "hypotheses": [
                    { "statement": "Payment-gateway timeouts caused by slow responses from payment-provider",
                      "confidence": "HIGH", "supportingEvidenceIds": ["%6$s", "%9$s", "%5$s", "%3$s"],
                      "contradictingEvidenceIds": [], "establishedCause": false },
                    { "statement": "The checkout-api v2.14.3 deployment caused the errors", "confidence": "LOW",
                      "supportingEvidenceIds": ["%4$s"], "contradictingEvidenceIds": ["%7$s", "%9$s"],
                      "establishedCause": false }
                  ],
                  "actions": [
                    { "order": 1, "type": "INVESTIGATE",
                      "description": "Check payment-gateway health and upstream provider status.",
                      "requiresApproval": false, "evidenceIds": ["%6$s", "%9$s"] },
                    { "order": 2, "type": "INVESTIGATE",
                      "description": "Review checkout-api v2.14.3 changes for timeout or retry modifications.",
                      "requiresApproval": false, "evidenceIds": ["%4$s"] },
                    { "order": 3, "type": "INVESTIGATE",
                      "description": "If the gateway remains unhealthy, follow the payment gateway degradation runbook.",
                      "requiresApproval": false, "evidenceIds": ["%8$s"] },
                    { "order": 4, "type": "ROLLBACK",
                      "description": "Rollback of checkout-api is not recommended on current evidence.",
                      "requiresApproval": true, "evidenceIds": ["%4$s", "%7$s"] },
                    { "order": 5, "type": "ESCALATE",
                      "description": "Page the payment-gateway on-call; do not restart services without on-call approval.",
                      "requiresApproval": false, "evidenceIds": ["%6$s"] }
                  ],
                  "openQuestions": ["Is payment-provider reporting an outage?"]
                }
                """.formatted(ids.incidentId(), ids.alert(), ids.errorRatio(), ids.deployment(), ids.timeoutLogs(),
                ids.gatewayLatency(), ids.flatErrorRatio(), ids.runbook(), ids.traces());
    }
}
