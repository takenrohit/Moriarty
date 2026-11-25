package dev.investigator.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.investigator.domain.Evidence;
import dev.investigator.domain.EvidenceRef;
import dev.investigator.domain.EvidenceType;
import dev.investigator.domain.InvestigationReport;
import dev.investigator.domain.InvestigationStatus;
import dev.investigator.report.ParseResult;
import dev.investigator.report.ReportDraftParser;
import dev.investigator.report.ReportValidator;
import dev.investigator.store.EvidenceRepository;
import dev.investigator.testsupport.DemoScenarioScript;
import dev.investigator.testsupport.InvestigatorSpringBootTest;
import dev.investigator.testsupport.ScenarioEvaluator;
import dev.investigator.testsupport.ScriptedChatModel;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Demo scenario evaluation with the Scripted_Chat_Model (NFR-43, Req 20.6, 22.2 to 22.7): the demo
 * alert goes in through the API, the {@link DemoScenarioScript} drives the real agent, tools, guard,
 * mock clients and report pipeline, and the stored report must pass every {@link ScenarioEvaluator}
 * check, conform to the Report_Draft_Schema and be available well within the 3-minute target.
 */
@InvestigatorSpringBootTest
@AutoConfigureMockMvc
class DemoScenarioEvaluationIT {

    /** Req 22.2. */
    private static final Duration INTAKE_LIMIT = Duration.ofSeconds(1);
    /** Req 22.3 allows 3 minutes; the scripted run must finish far sooner. */
    private static final Duration REPORT_LIMIT = Duration.ofSeconds(30);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ScriptedChatModel model;
    @Autowired EvidenceRepository evidence;
    @Autowired ReportDraftParser parser;
    @Autowired ReportValidator validator;

    @BeforeEach
    void resetModel() {
        model.reset();
    }

    @Test
    void demoScenarioReportPassesEveryEvaluatorCheck() throws Exception {
        DemoScenarioScript.enqueue(model);

        long posted = System.nanoTime();
        String accepted = mvc.perform(post("/api/v1/alerts").contentType(MediaType.APPLICATION_JSON)
                        .content(DemoScenarioScript.DEMO_ALERT.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        Duration intake = Duration.ofNanos(System.nanoTime() - posted);
        assertThat(intake).as("time to return the Incident_ID").isLessThan(INTAKE_LIMIT);
        String incidentId = mapper.readTree(accepted).get("incidentId").asText();

        JsonNode json = awaitJsonReport(incidentId, posted);
        InvestigationReport report = mapper.treeToValue(json, InvestigationReport.class);
        assertThat(report.status()).as("report status, summary: %s", report.summary())
                .isEqualTo(InvestigationStatus.COMPLETED);
        assertThat(model.remainingSteps()).isZero();
        List<ToolExecutionResultMessage> results =
                ScriptedChatModel.toolResults(model.requests().get(model.requests().size() - 1));
        assertThat(results).extracting(ToolExecutionResultMessage::toolName)
                .containsExactlyElementsOf(DemoScenarioScript.TOOL_SEQUENCE);

        // The script found the evidence of demo-scenario.md ev-1 to ev-7.
        assertThat(report.evidence()).extracting(EvidenceRef::type).containsExactly(EvidenceType.ALERT,
                EvidenceType.METRIC, EvidenceType.DEPLOYMENT, EvidenceType.LOG, EvidenceType.METRIC,
                EvidenceType.METRIC, EvidenceType.RUNBOOK, EvidenceType.TRACE);
        assertThat(report.evidence()).extracting(EvidenceRef::headline).satisfiesExactly(
                alert -> assertThat(alert).contains("HighErrorRate"),
                errorRatio -> assertThat(errorRatio).contains("from 0.002 to 0.12"),
                deploy -> assertThat(deploy).contains(ScenarioEvaluator.DEMO_DEPLOYMENT),
                logs -> assertThat(logs).contains("214 matches", "PaymentGatewayTimeoutException"),
                latency -> assertThat(latency).contains("payment-gateway", "from 180 to 5000"),
                flat -> assertThat(flat).contains("steady at 0.002"),
                runbook -> assertThat(runbook).contains("Payment gateway degradation"),
                traces -> assertThat(traces).contains("with errors"));
        assertThat(results.get(6).text()).contains("POST /authorize", "payment-provider");

        String markdown = mvc.perform(get("/api/v1/incidents/{id}/report.md", incidentId))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/markdown"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String> stored = evidence.findByIncident(incidentId).stream().map(Evidence::id).toList();

        assertThat(new ScenarioEvaluator().evaluate(report, stored, markdown))
                .as("failed Scenario_Evaluator checks; Markdown:%n%s", markdown)
                .isEmpty();

        // Schema-valid (Req 22.3): the report's draft fields conform to the Report_Draft_Schema, and the
        // report passes the checks the store applies.
        assertThat(parser.parse(draftView(json).toString())).isInstanceOf(ParseResult.Ok.class);
        assertThat(validator.validateReport(report).violations()).isEmpty();
    }

    /** Polls the JSON report until it is stored; fails after {@link #REPORT_LIMIT} from {@code posted}. */
    private JsonNode awaitJsonReport(String incidentId, long posted) throws Exception {
        long deadline = posted + REPORT_LIMIT.toNanos();
        while (System.nanoTime() < deadline) {
            MvcResult result = mvc.perform(get("/api/v1/incidents/{id}/report", incidentId)).andReturn();
            int code = result.getResponse().getStatus();
            if (code == 200) {
                return mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
            }
            assertThat(code).isEqualTo(202);
            Thread.sleep(20);
        }
        throw new AssertionError("no report for " + incidentId + " within " + REPORT_LIMIT);
    }

    /**
     * The report JSON reduced to the Report_Draft_Schema fields: {@code timeline.entries} becomes
     * {@code timeline}; {@code status}, {@code evidence} and {@code generatedAt} are dropped.
     */
    private static JsonNode draftView(JsonNode report) {
        ObjectNode draft = report.deepCopy();
        draft.set("timeline", report.get("timeline").get("entries"));
        draft.remove(List.of("status", "evidence", "generatedAt"));
        return draft;
    }
}
