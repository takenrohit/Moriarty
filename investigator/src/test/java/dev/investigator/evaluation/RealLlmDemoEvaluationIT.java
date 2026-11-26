package dev.investigator.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.investigator.domain.Evidence;
import dev.investigator.domain.InvestigationReport;
import dev.investigator.domain.InvestigationStatus;
import dev.investigator.store.EvidenceRepository;
import dev.investigator.testsupport.DemoScenarioScript;
import dev.investigator.testsupport.ScenarioEvaluator;
import dev.investigator.testsupport.ScenarioEvaluator.FailedCheck;
import dev.investigator.testsupport.ScriptedChatModel;
import dev.langchain4j.model.bedrock.BedrockChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIf;
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
 * Demo scenario evaluation against the configured LLM, OpenAI-compatible or Bedrock (Req 11.13, 14.13,
 * 20.7): 5 runs, each must produce a report within 3 minutes that passes every {@link ScenarioEvaluator}
 * check.
 *
 * <p>Tagged {@code llm}, so it runs only with {@code -Pllm-eval} (Req 20.8). The class is disabled
 * unless {@code LLM_API_KEY} or {@code AWS_BEARER_TOKEN_BEDROCK} is set ({@link RealLlmKeys}); the
 * condition is evaluated before the Spring context starts, because {@code LlmConfig} fails startup
 * without a key. With a Bedrock key also set {@code LLM_PROVIDER=bedrock}. The context uses the production Chat_Model,
 * not {@code TestLlmConfig}'s scripted one.
 *
 * <p>A run whose investigation failed on a provider 429 or 5xx is repeated (up to {@value #MAX_ATTEMPTS}
 * attempts) instead of being scored, because that failure says nothing about the model's analysis.
 */
@Tag("llm")
@EnabledIf(value = "dev.investigator.evaluation.RealLlmKeys#present",
        disabledReason = "neither LLM_API_KEY nor AWS_BEARER_TOKEN_BEDROCK is set")
@SpringBootTest(properties = "investigator.llm.enabled=true")
@AutoConfigureMockMvc
class RealLlmDemoEvaluationIT {

    private static final Logger log = LoggerFactory.getLogger(RealLlmDemoEvaluationIT.class);

    /** Req 14.13, 22.3. */
    private static final Duration REPORT_LIMIT = Duration.ofMinutes(3);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    /** Attempts per run when the provider answers 429 or 5xx (LangChain4j exception names). */
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration TRANSIENT_PAUSE = Duration.ofSeconds(65);
    private static final List<String> TRANSIENT_ERRORS = List.of("RateLimitException", "InternalServerException");
    /** Each report's Markdown is written here (git-ignored build output) for inspection. */
    private static final Path OUTPUT_DIR = Path.of("target", "llm-eval");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired EvidenceRepository evidence;
    @Autowired ApplicationContext context;
    @Autowired ChatModel chatModel;

    @RepeatedTest(5)
    void demoScenarioPassesEveryEvaluatorCheckWithTheRealLlm(RepetitionInfo repetition) throws Exception {
        assertThat(context.getBeanNamesForType(ScriptedChatModel.class)).as("scripted Chat_Model beans").isEmpty();
        assertThat(chatModel).isInstanceOfAny(OpenAiChatModel.class, BedrockChatModel.class);

        int run = repetition.getCurrentRepetition();
        Run result = investigate(run, 1);
        // A provider rate limit or overload (429/5xx) says nothing about the model's analysis, so the run
        // is repeated after a pause instead of being scored; each attempt still gets the full 3 minutes.
        for (int attempt = 2; attempt <= MAX_ATTEMPTS && result.providerTransientFailure(); attempt++) {
            log.warn("Real-LLM demo run {}: provider transient failure ({}); retrying in {} s",
                    run, result.report().summary(), TRANSIENT_PAUSE.toSeconds());
            Thread.sleep(TRANSIENT_PAUSE.toMillis());
            result = investigate(run, attempt);
        }
        assertThat(result.duration()).as("time until the report is available").isLessThanOrEqualTo(REPORT_LIMIT);

        InvestigationReport report = result.report();
        String markdown = result.markdown();
        List<String> stored = evidence.findByIncident(result.incidentId()).stream().map(Evidence::id).toList();

        List<FailedCheck> failed = new ScenarioEvaluator().evaluate(report, stored, markdown);
        assertThat(failed)
                .as("failed Scenario_Evaluator checks in run %d (status %s, summary: %s); Markdown:%n%s",
                        run, report.status(), report.summary(), markdown)
                .isEmpty();
    }

    /** Posts the demo alert, waits for the report and saves its Markdown under {@link #OUTPUT_DIR}. */
    private Run investigate(int run, int attempt) throws Exception {
        long posted = System.nanoTime();
        String accepted = mvc.perform(post("/api/v1/alerts").contentType(MediaType.APPLICATION_JSON)
                        .content(DemoScenarioScript.DEMO_ALERT.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String incidentId = mapper.readTree(accepted).get("incidentId").asText();

        String json = awaitJsonReport(incidentId, posted);
        Duration duration = Duration.ofNanos(System.nanoTime() - posted);
        InvestigationReport report = mapper.readValue(json, InvestigationReport.class);
        String markdown = mvc.perform(get("/api/v1/incidents/{id}/report.md", incidentId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        log.info("Real-LLM demo run {} attempt {}: incident {} status {} report available after {} ms",
                run, attempt, incidentId, report.status(), duration.toMillis());
        Files.createDirectories(OUTPUT_DIR);
        Files.writeString(OUTPUT_DIR.resolve("run-" + run + "-attempt-" + attempt + ".md"), markdown,
                StandardCharsets.UTF_8);
        return new Run(incidentId, duration, report, markdown);
    }

    private record Run(String incidentId, Duration duration, InvestigationReport report, String markdown) {

        /** True when the investigation failed on an HTTP 429 or 5xx from the LLM provider. */
        boolean providerTransientFailure() {
            return report.status() == InvestigationStatus.FAILED && report.summary() != null
                    && TRANSIENT_ERRORS.stream().anyMatch(report.summary()::contains);
        }
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
