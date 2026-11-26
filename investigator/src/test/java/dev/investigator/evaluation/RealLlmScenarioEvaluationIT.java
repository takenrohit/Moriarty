package dev.investigator.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.investigator.domain.Evidence;
import dev.investigator.domain.InvestigationReport;
import dev.investigator.domain.InvestigationStatus;
import dev.investigator.integration.mock.ScenarioKind;
import dev.investigator.store.EvidenceRepository;
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
 * Scenario_Library evaluation against the configured LLM, OpenAI-compatible or Bedrock (reliability
 * Req 10.4, 11.13): one run per non-default scenario, whose report must pass {@link ScenarioEvaluator}
 * for that scenario's expected cause within 3 minutes. The default scenario is covered by
 * {@link RealLlmDemoEvaluationIT}.
 *
 * <p>Tagged {@code llm} (runs only with {@code -Pllm-eval}) and disabled unless {@code LLM_API_KEY} or
 * {@code AWS_BEARER_TOKEN_BEDROCK} is set ({@link RealLlmKeys}), like {@link RealLlmDemoEvaluationIT};
 * a run that failed on a provider 429 or 5xx is repeated.
 */
@Tag("llm")
@EnabledIf(value = "dev.investigator.evaluation.RealLlmKeys#present",
        disabledReason = "neither LLM_API_KEY nor AWS_BEARER_TOKEN_BEDROCK is set")
@SpringBootTest(properties = "investigator.llm.enabled=true")
@AutoConfigureMockMvc
class RealLlmScenarioEvaluationIT {

    private static final Logger log = LoggerFactory.getLogger(RealLlmScenarioEvaluationIT.class);

    private static final Duration REPORT_LIMIT = Duration.ofMinutes(3);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration TRANSIENT_PAUSE = Duration.ofSeconds(65);
    private static final List<String> TRANSIENT_ERRORS = List.of("RateLimitException", "InternalServerException");
    private static final Path OUTPUT_DIR = Path.of("target", "llm-eval");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired EvidenceRepository evidence;
    @Autowired ApplicationContext context;
    @Autowired ChatModel chatModel;

    @ParameterizedTest
    @EnumSource(value = ScenarioKind.class, names = {"BAD_DEPLOY", "DB_POOL_EXHAUSTION", "NOISY_NEIGHBOUR"})
    void scenarioPassesTheEvaluatorWithTheRealLlm(ScenarioKind kind) throws Exception {
        assertThat(context.getBeanNamesForType(ScriptedChatModel.class)).as("scripted Chat_Model beans").isEmpty();
        assertThat(chatModel).isInstanceOfAny(OpenAiChatModel.class, BedrockChatModel.class);

        Run result = investigate(kind, 1);
        for (int attempt = 2; attempt <= MAX_ATTEMPTS && result.providerTransientFailure(); attempt++) {
            log.warn("Real-LLM {} run: provider transient failure ({}); retrying in {} s",
                    kind, result.report().summary(), TRANSIENT_PAUSE.toSeconds());
            Thread.sleep(TRANSIENT_PAUSE.toMillis());
            result = investigate(kind, attempt);
        }
        assertThat(result.duration()).as("time until the report is available").isLessThanOrEqualTo(REPORT_LIMIT);

        List<String> stored = evidence.findByIncident(result.incidentId()).stream().map(Evidence::id).toList();
        List<FailedCheck> failed = new ScenarioEvaluator().evaluate(result.report(), stored, result.markdown(),
                kind.expectedCause());
        assertThat(failed)
                .as("failed Scenario_Evaluator checks for %s (status %s, summary: %s); Markdown:%n%s",
                        kind, result.report().status(), result.report().summary(), result.markdown())
                .isEmpty();
    }

    private Run investigate(ScenarioKind kind, int attempt) throws Exception {
        long posted = System.nanoTime();
        String body = ScenarioEvaluationIT.alert(ScenarioEvaluationIT.alertName(kind), ScenarioEvaluationIT.label(kind));
        String accepted = mvc.perform(post("/api/v1/alerts").contentType(MediaType.APPLICATION_JSON)
                        .content(body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String incidentId = mapper.readTree(accepted).get("incidentId").asText();

        String json = awaitJsonReport(incidentId, posted);
        Duration duration = Duration.ofNanos(System.nanoTime() - posted);
        InvestigationReport report = mapper.readValue(json, InvestigationReport.class);
        String markdown = mvc.perform(get("/api/v1/incidents/{id}/report.md", incidentId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        log.info("Real-LLM {} attempt {}: incident {} status {} report available after {} ms",
                kind, attempt, incidentId, report.status(), duration.toMillis());
        Files.createDirectories(OUTPUT_DIR);
        Files.writeString(OUTPUT_DIR.resolve("scenario-" + kind.name().toLowerCase(java.util.Locale.ROOT) + "-attempt-" + attempt + ".md"),
                markdown, StandardCharsets.UTF_8);
        return new Run(incidentId, duration, report, markdown);
    }

    private record Run(String incidentId, Duration duration, InvestigationReport report, String markdown) {

        boolean providerTransientFailure() {
            return report.status() == InvestigationStatus.FAILED && report.summary() != null
                    && TRANSIENT_ERRORS.stream().anyMatch(report.summary()::contains);
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
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        throw new AssertionError("no report for " + incidentId + " within " + REPORT_LIMIT);
    }
}
