package dev.investigator.report;

import static dev.investigator.testsupport.ScriptedChatModel.call;
import static dev.investigator.testsupport.ScriptedChatModel.finalAnswer;
import static dev.investigator.testsupport.ScriptedChatModel.toolCalls;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.investigator.domain.InvestigationStatus;
import dev.investigator.store.IncidentRepository;
import dev.investigator.store.InvestigationRepository;
import dev.investigator.testsupport.InvestigatorSpringBootTest;
import dev.investigator.testsupport.ScriptedChatModel;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * First end-to-end run (plan 1.11): the demo alert goes in through the API, the Scripted_Chat_Model
 * calls two tools and returns a valid draft citing the Evidence_IDs the tools returned, and the report
 * comes back as JSON (UTC), Markdown (IST) and the HTML page (Req 6.7, 16.7, 17.1, 17.3 to 17.5).
 */
@InvestigatorSpringBootTest
@AutoConfigureMockMvc
class ReportEndToEndSmokeTest {

    private static final String DEMO_ALERT = """
            {
              "alert": "HighErrorRate",
              "service": "checkout-api",
              "severity": "critical",
              "detectedAt": "2026-11-23T14:40:00Z",
              "labels": { "environment": "production", "region": "ap-south-1" },
              "dashboardLinks": ["http://localhost:3000/d/checkout"]
            }
            """;
    private static final String METRIC_ARGS = """
            {"service": "checkout-api",
             "promql": "sum(rate(http_requests_total{service=\\"checkout-api\\",status=~\\"5..\\"}[5m])) / sum(rate(http_requests_total{service=\\"checkout-api\\"}[5m]))",
             "start": "2026-11-23T13:40:00Z", "end": "2026-11-23T14:50:00Z"}
            """;
    private static final String CHANGES_ARGS = "{\"service\": \"checkout-api\", \"lookbackHours\": 2}";
    private static final String DEPLOY_HEADLINE = "checkout-api v2.14.3 deployed by dev-alice";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ScriptedChatModel model;
    @Autowired IncidentRepository incidents;
    @Autowired InvestigationRepository investigations;

    @BeforeEach
    void resetModel() {
        model.reset();
    }

    /** Keeps this test's background investigation away from later tests' scripts. */
    @AfterEach
    void awaitStartedInvestigations() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline && incidents.findAllNewestFirst().stream()
                .map(i -> investigations.find(i.id()))
                .flatMap(Optional::stream)
                .anyMatch(i -> i.status() == InvestigationStatus.PENDING || i.status() == InvestigationStatus.RUNNING)) {
            Thread.sleep(10);
        }
    }

    @Test
    void demoAlertProducesJsonMarkdownAndHtmlReports() throws Exception {
        model.enqueue(toolCalls(call("getMetric", METRIC_ARGS), call("getRecentChanges", CHANGES_ARGS)),
                request -> finalAnswer(draftCiting(ScriptedChatModel.toolResults(request))).apply(request));

        String accepted = mvc.perform(post("/api/v1/alerts").contentType(MediaType.APPLICATION_JSON)
                        .content(DEMO_ALERT.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String incidentId = mapper.readTree(accepted).get("incidentId").asText();
        String alert = "ev-" + incidentId + "-1";
        String metric = "ev-" + incidentId + "-2";
        String deploy = "ev-" + incidentId + "-3";

        // JSON report: 200 once stored, times in UTC.
        JsonNode report = awaitJsonReport(incidentId);
        assertThat(report.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(report.get("timeline").get("entries").get(0).get("at").asText()).isEqualTo("2026-11-23T14:26:00Z");
        assertThat(report.get("hypotheses").get(1).get("establishedCause").asBoolean()).isFalse();
        assertThat(report.get("actions").get(1).get("requiresApproval").asBoolean()).isTrue();
        assertThat(report.get("evidence").findValuesAsText("id")).containsExactly(alert, metric, deploy);
        assertThat(report.get("evidence").findValuesAsText("type")).containsExactly("ALERT", "METRIC", "DEPLOYMENT");
        assertThat(report.get("generatedAt").asText()).endsWith("Z");
        assertThat(model.remainingSteps()).isZero();

        mvc.perform(get("/api/v1/incidents/{id}", incidentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(incidentId))
                .andExpect(jsonPath("$.status").value("REPORTED"))
                .andExpect(jsonPath("$.investigationStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.detectedAt").value("2026-11-23T14:40:00Z"));

        // Markdown: text/markdown, times in IST, correlation statement for the deploy.
        MvcResult md = mvc.perform(get("/api/v1/incidents/{id}/report.md", incidentId))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/markdown"))
                .andReturn();
        String markdown = md.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(markdown)
                .startsWith("# Incident Investigation — checkout-api\n")
                .contains("Severity: Critical\n", "Detected: 2026-11-23 20:10 IST\n", "Status: COMPLETED\n")
                .contains("## Timeline (IST)\n")
                .contains("19:56  checkout-api v2.14.3 deployed [" + deploy + "]\n")
                .contains("20:08  checkout-api 5xx rate starts rising [" + metric + "]\n")
                .contains("20:10  HighErrorRate alert fires [" + alert + "]\n")
                .contains("1. Payment-gateway timeouts caused by slow responses from payment-provider"
                        + " (confidence: HIGH) [" + metric + "]\n")
                .contains("   Contradicting: [" + metric + "]\n")
                .contains("   " + ReportRenderer.correlationStatement(DEPLOY_HEADLINE) + "\n")
                .contains("2. Rollback of checkout-api is not recommended on current evidence. [" + deploy
                        + "] [rollback, requires approval]\n")
                .contains("- Is payment-provider degraded?\n")
                .contains("- " + deploy + " DEPLOYMENT changes 19:56 — " + DEPLOY_HEADLINE + "\n")
                .doesNotContain("14:26", "14:40");
        assertThat(markdown.split(java.util.regex.Pattern.quote("Correlated with"), -1)).hasSize(2);

        // HTML pages.
        String page = mvc.perform(get("/incidents/{id}", incidentId))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(page)
                .contains("Incident Investigation — checkout-api", "2026-11-23 20:10 IST", "Timeline (IST)")
                .contains(ReportRenderer.correlationStatement(DEPLOY_HEADLINE))
                .contains("19:56", "[rollback, requires approval]", "[" + deploy + "]");
        String list = mvc.perform(get("/incidents"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(list).contains("/incidents/" + incidentId, "HighErrorRate on checkout-api");
    }

    private JsonNode awaitJsonReport(String incidentId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            MvcResult result = mvc.perform(get("/api/v1/incidents/{id}/report", incidentId)).andReturn();
            int code = result.getResponse().getStatus();
            JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
            if (code == 200) {
                return body;
            }
            assertThat(code).isEqualTo(202);
            assertThat(body.get("status").asText()).isIn("PENDING", "RUNNING", "COMPLETED");
            Thread.sleep(20);
        }
        throw new AssertionError("no report for " + incidentId + " within 10 s");
    }

    /** The draft the model returns, citing the Evidence_IDs found in the tool results. */
    private String draftCiting(List<ToolExecutionResultMessage> results) {
        assertThat(results).hasSize(2);
        String metric = json(results.get(0).text()).get("evidenceId").asText();
        String deploy = json(results.get(1).text()).get("changes").get(0).get("evidenceId").asText();
        String incidentId = metric.substring("ev-".length(), metric.lastIndexOf('-'));
        String alert = "ev-" + incidentId + "-1";
        return """
                {
                  "incidentId": "%1$s",
                  "summary": "The checkout-api error rate rose from 0.2%% to 12%% at 20:08 IST [%2$s].",
                  "timeline": [
                    { "at": "2026-11-23T14:26:00Z", "description": "checkout-api v2.14.3 deployed",
                      "evidenceIds": ["%3$s"] },
                    { "at": "2026-11-23T14:38:00Z", "description": "checkout-api 5xx rate starts rising",
                      "evidenceIds": ["%2$s"] },
                    { "at": "2026-11-23T14:40:00Z", "description": "HighErrorRate alert fires",
                      "evidenceIds": ["%4$s"] }
                  ],
                  "hypotheses": [
                    { "statement": "Payment-gateway timeouts caused by slow responses from payment-provider",
                      "confidence": "HIGH", "supportingEvidenceIds": ["%2$s"], "contradictingEvidenceIds": [],
                      "establishedCause": false },
                    { "statement": "The checkout-api v2.14.3 deploy caused the errors", "confidence": "LOW",
                      "supportingEvidenceIds": ["%3$s"], "contradictingEvidenceIds": ["%2$s"],
                      "establishedCause": false }
                  ],
                  "actions": [
                    { "order": 1, "type": "INVESTIGATE",
                      "description": "Check payment-gateway health and upstream provider status.",
                      "requiresApproval": false, "evidenceIds": ["%2$s"] },
                    { "order": 2, "type": "ROLLBACK",
                      "description": "Rollback of checkout-api is not recommended on current evidence.",
                      "requiresApproval": true, "evidenceIds": ["%3$s"] }
                  ],
                  "openQuestions": ["Is payment-provider degraded?"]
                }
                """.formatted(incidentId, metric, deploy, alert);
    }

    private JsonNode json(String text) {
        try {
            return mapper.readTree(text);
        } catch (JsonProcessingException e) {
            throw new AssertionError("tool result is not JSON: " + text, e);
        }
    }
}
