package dev.investigator.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.investigator.domain.Evidence;
import dev.investigator.domain.InvestigationReport;
import dev.investigator.store.EvidenceRepository;
import dev.investigator.testsupport.DemoScenarioScript;
import dev.investigator.testsupport.ScenarioEvaluator;
import dev.investigator.testsupport.ScenarioEvaluator.FailedCheck;
import dev.investigator.testsupport.ScriptedChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Demo scenario evaluation against the configured OpenAI-compatible LLM (Req 14.13, 20.7): 5 runs,
 * each must produce a report within 3 minutes that passes every {@link ScenarioEvaluator} check.
 *
 * <p>Tagged {@code llm}, so it runs only with {@code -Pllm-eval} (Req 20.8). The class is disabled
 * unless {@code LLM_API_KEY} is set; the condition is evaluated before the Spring context starts,
 * because {@code LlmConfig} fails startup without a key. The context uses the production Chat_Model,
 * not {@code TestLlmConfig}'s scripted one.
 */
@Tag("llm")
@EnabledIfEnvironmentVariable(named = "LLM_API_KEY", matches = ".*\\S.*",
        disabledReason = "LLM_API_KEY is not set")
@SpringBootTest(properties = "investigator.llm.enabled=true")
@AutoConfigureMockMvc
class RealLlmDemoEvaluationIT {

    private static final Logger log = LoggerFactory.getLogger(RealLlmDemoEvaluationIT.class);

    /** Req 14.13, 22.3. */
    private static final Duration REPORT_LIMIT = Duration.ofMinutes(3);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired EvidenceRepository evidence;
    @Autowired ApplicationContext context;
    @Autowired ChatModel chatModel;

    @RepeatedTest(5)
    void demoScenarioPassesEveryEvaluatorCheckWithTheRealLlm(RepetitionInfo repetition) throws Exception {
        assertThat(context.getBeanNamesForType(ScriptedChatModel.class)).as("scripted Chat_Model beans").isEmpty();
        assertThat(chatModel).isInstanceOf(OpenAiChatModel.class);

        long posted = System.nanoTime();
        String accepted = mvc.perform(post("/api/v1/alerts").contentType(MediaType.APPLICATION_JSON)
                        .content(DemoScenarioScript.DEMO_ALERT.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String incidentId = mapper.readTree(accepted).get("incidentId").asText();

        String json = awaitJsonReport(incidentId, posted);
        Duration duration = Duration.ofNanos(System.nanoTime() - posted);
        log.info("Real-LLM demo run {}/{}: incident {} report available after {} ms",
                repetition.getCurrentRepetition(), repetition.getTotalRepetitions(), incidentId,
                duration.toMillis());
        assertThat(duration).as("time until the report is available").isLessThanOrEqualTo(REPORT_LIMIT);

        InvestigationReport report = mapper.readValue(json, InvestigationReport.class);
        String markdown = mvc.perform(get("/api/v1/incidents/{id}/report.md", incidentId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String> stored = evidence.findByIncident(incidentId).stream().map(Evidence::id).toList();

        List<FailedCheck> failed = new ScenarioEvaluator().evaluate(report, stored, markdown);
        assertThat(failed)
                .as("failed Scenario_Evaluator checks in run %d (status %s, summary: %s); Markdown:%n%s",
                        repetition.getCurrentRepetition(), report.status(), report.summary(), markdown)
                .isEmpty();
    }

    /** Polls the JSON report until it is stored; fails after {@link #REPORT_LIMIT} from {@code posted}. */
    private String awaitJsonReport(String incidentId, long posted) throws Exception {
        long deadline = posted + REPORT_LIMIT.toNanos();
        while (System.nanoTime() < deadline) {
            MvcResult result = mvc.perform(get("/api/v1/incidents/{id}/report", incidentId)).andReturn();
            int code = result.getResponse().getStatus();
            if (code == 200) {
                return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            }
            assertThat(code).isEqualTo(202);
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        throw new AssertionError("no report for " + incidentId + " within " + REPORT_LIMIT);
    }
}
