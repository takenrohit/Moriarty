package dev.investigator.investigation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.investigator.config.InvestigatorProperties;
import dev.investigator.domain.Incident;
import dev.investigator.domain.IncidentStatus;
import dev.investigator.domain.InvestigationReport;
import dev.investigator.domain.InvestigationStatus;
import dev.investigator.domain.Severity;
import dev.investigator.store.IncidentRepository;
import dev.investigator.store.ReportRepository;
import dev.investigator.testsupport.InvestigatorSpringBootTest;
import dev.investigator.testsupport.ScriptedChatModel;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Once the reported input plus output tokens reach {@code investigator.budget.max-llm-tokens}, no further
 * LLM request is sent and the investigation ends {@code INCONCLUSIVE} with a token-budget open question
 * (Req 6.5).
 */
@InvestigatorSpringBootTest
class TokenBudgetTest {

    private static final Instant DETECTED_AT = Instant.parse("2026-11-23T14:40:00Z");

    @Autowired InvestigationOrchestrator orchestrator;
    @Autowired ScriptedChatModel model;
    @Autowired IncidentRepository incidents;
    @Autowired ReportRepository reports;
    @Autowired InvestigatorProperties properties;

    long budget;

    @BeforeEach
    void setUp() {
        model.reset();
        budget = properties.budget().maxLlmTokens();
    }

    @Test
    void budgetReachedInTheToolLoopStopsFurtherRequests() throws Exception {
        Incident incident = newIncident();
        // The tool-call response alone uses the whole budget; the request after the tool result is refused.
        model.enqueue(withUsage(ScriptedChatModel.toolCall("findRunbook",
                "{\"service\":\"checkout-api\",\"symptom\":\"high error rate\"}"), budget - 10, 10));

        orchestrator.start(incident);

        InvestigationReport report = awaitReport(incident.id());
        assertThat(report.status()).isEqualTo(InvestigationStatus.INCONCLUSIVE);
        assertThat(report.openQuestions()).first().asString().contains("LLM token budget of " + budget);
        assertThat(model.requests()).hasSize(1);
        assertThat(model.remainingSteps()).isZero();
    }

    @Test
    void budgetReachedByAnInvalidDraftSkipsTheCorrectionRequest() throws Exception {
        Incident incident = newIncident();
        model.enqueue(withUsage(ScriptedChatModel.finalAnswer("not a report"), budget, 0),
                ScriptedChatModel.finalAnswer("never requested"));

        orchestrator.start(incident);

        InvestigationReport report = awaitReport(incident.id());
        assertThat(report.status()).isEqualTo(InvestigationStatus.INCONCLUSIVE);
        assertThat(report.openQuestions()).first().asString().contains("LLM token budget of " + budget);
        assertThat(model.requests()).hasSize(1);
        assertThat(model.remainingSteps()).isEqualTo(1);
    }

    @Test
    void tokensAreAccumulatedFromEachResponse() {
        InvestigationContext ctx = new InvestigationContext("inc-0123456789ab", DETECTED_AT, DETECTED_AT,
                DETECTED_AT, "0".repeat(32), "ev-inc-0123456789ab-1", DETECTED_AT, 15, 100);
        ctx.addLlmTokens(TokenBudgetChatModel.tokens(new TokenUsage(60, 30)));
        assertThat(ctx.tokenBudgetReached()).isFalse();
        ctx.addLlmTokens(TokenBudgetChatModel.tokens(new TokenUsage(10, null)));
        assertThat(ctx.llmTokens()).isEqualTo(100);
        assertThat(ctx.tokenBudgetReached()).isTrue();
    }

    /** {@code step}'s response with the given token usage. */
    private static Function<ChatRequest, ChatResponse> withUsage(Function<ChatRequest, ChatResponse> step,
            long input, long output) {
        return request -> {
            AiMessage message = step.apply(request).aiMessage();
            return ChatResponse.builder()
                    .aiMessage(message)
                    .finishReason(message.hasToolExecutionRequests() ? FinishReason.TOOL_EXECUTION : FinishReason.STOP)
                    .tokenUsage(new TokenUsage((int) input, (int) output))
                    .build();
        };
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
                List.of(), "e".repeat(64), IncidentStatus.OPEN);
        incidents.save(incident);
        return incident;
    }
}
