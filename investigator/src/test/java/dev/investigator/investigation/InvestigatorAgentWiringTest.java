package dev.investigator.investigation;

import static dev.investigator.testsupport.ScriptedChatModel.finalAnswer;
import static dev.investigator.testsupport.ScriptedChatModel.toolCall;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.investigator.config.InvestigatorProperties;
import dev.investigator.domain.Incident;
import dev.investigator.domain.IncidentStatus;
import dev.investigator.domain.Severity;
import dev.investigator.testsupport.InvestigatorSpringBootTest;
import dev.investigator.testsupport.ScriptedChatModel;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Wiring of the Investigator_Agent with the Scripted_Chat_Model: prompts, tools, chat memory and the
 * round-trip limit (Req 13.1, 13.4, 13.5, 13.6, 19.2), and the error results for malformed tool
 * arguments and unknown tool names (CP-35 e). Unless a test registers one, no investigation context
 * exists, so every tool call returns the "not running" error without touching any integration.
 */
@InvestigatorSpringBootTest
class InvestigatorAgentWiringTest {

    private static final String INCIDENT_ID = "inc-00000000a11e";
    private static final String ALERT_EVIDENCE_ID = "ev-" + INCIDENT_ID + "-1";
    private static final String WINDOW_START = "2026-11-23T13:40:00Z";
    private static final String WINDOW_END = "2026-11-23T14:50:00Z";
    private static final String METRIC_ARGS = "{\"service\":\"checkout-api\",\"promql\":\"up\","
            + "\"start\":\"" + WINDOW_START + "\",\"end\":\"" + WINDOW_END + "\"}";

    @Autowired
    InvestigatorAgent agent;

    @Autowired
    ScriptedChatModel model;

    @Autowired
    PromptInputs promptInputs;

    @Autowired
    InvestigatorProperties properties;

    @Autowired
    InvestigationContextRegistry contexts;

    @BeforeEach
    void resetModel() {
        model.reset();
    }

    @AfterEach
    void evictMemory() {
        agent.evictChatMemory(INCIDENT_ID);
    }

    @Test
    void firstRequestCarriesPromptsAndTheFiveTools() {
        Incident incident = new Incident(INCIDENT_ID, "HighErrorRate on checkout-api", Severity.CRITICAL,
                "checkout-api", Instant.parse("2026-11-23T14:40:00Z"), "HighErrorRate",
                Map.of("env", "prod"), List.of(), "f".repeat(64), IncidentStatus.OPEN);

        String answer = investigate(promptInputs.incidentJson(incident));

        assertThat(answer).isEqualTo(ScriptedChatModel.DEFAULT_FINAL_ANSWER);
        assertThat(model.requests()).hasSize(1);
        ChatRequest first = model.requests().get(0);
        assertThat(first.messages().get(0)).isInstanceOfSatisfying(SystemMessage.class,
                m -> assertThat(m.text()).contains("You cannot change systems")
                        .contains("Order hypotheses from most likely to least likely")
                        .contains("must return a single series"));
        assertThat(first.messages().get(1)).isInstanceOfSatisfying(UserMessage.class,
                m -> assertThat(m.singleText()).contains(promptInputs.incidentJson(incident))
                        .contains("\"detectedAt\":\"2026-11-23T14:40:00Z\"")
                        .contains(WINDOW_START).contains(WINDOW_END).contains(ALERT_EVIDENCE_ID)
                        .contains(promptInputs.reportSchema())
                        .doesNotContain("{{"));
        assertThat(first.toolSpecifications()).extracting(ToolSpecification::name)
                .containsExactlyInAnyOrder("getMetric", "searchLogs", "findProblemTraces", "getRecentChanges",
                        "findRunbook");
        assertThat(agent.getChatMemory(INCIDENT_ID).messages()).hasSize(3);
    }

    @Test
    void scriptedToolCallReachesTheToolsAndItsResultReachesTheNextStep() {
        model.enqueue(
                toolCall("getMetric", METRIC_ARGS),
                request -> finalAnswer(ScriptedChatModel.lastToolResult(request)
                        .map(ToolExecutionResultMessage::text).orElse("none")).apply(request));

        String answer = investigate("{}");

        assertThat(answer).contains("investigation is not running");
        assertThat(model.requests()).hasSize(2);
        assertThat(ScriptedChatModel.toolResults(model.requests().get(1)))
                .singleElement().satisfies(r -> assertThat(r.toolName()).isEqualTo("getMetric"));
    }

    /**
     * LangChain4j counts every model response against {@code maxToolCallingRoundTrips}, the final
     * answer included. With the limit at budget + 2 the model gets budget + 1 tool rounds (the budget
     * plus the one the guard refuses) and then must answer.
     */
    @Test
    void toolCallingRoundTripsAreLimitedToBudgetPlusTwo() {
        int limit = properties.budget().maxToolCalls() + 2;
        for (int i = 0; i < limit - 1; i++) {
            model.enqueue(toolCall("getMetric", METRIC_ARGS));
        }
        model.enqueue(finalAnswer("{\"done\":true}"));
        assertThat(investigate("{}")).isEqualTo("{\"done\":true}");
        assertThat(model.requests()).hasSize(limit);

        agent.evictChatMemory(INCIDENT_ID);
        model.reset();
        for (int i = 0; i < limit; i++) {
            model.enqueue(toolCall("getMetric", METRIC_ARGS));
        }
        model.enqueue(finalAnswer("{\"done\":true}"));
        assertThatThrownBy(() -> investigate("{}"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("maxToolCallingRoundTrips");
    }

    @Test
    void malformedToolArgumentsReturnAnErrorResultAndCountAgainstTheBudget() {
        InvestigationContext ctx = new InvestigationContext(INCIDENT_ID, Instant.parse(WINDOW_START),
                Instant.parse(WINDOW_END), Instant.parse(WINDOW_END), "0".repeat(31) + "1", ALERT_EVIDENCE_ID,
                Instant.parse("2026-11-23T14:40:00Z"), 15);
        ctx.accepting().set(true);
        contexts.register(ctx);
        try {
            model.enqueue(
                    toolCall("getMetric", "{\"service\": \"checkout-api\", \"promql\": "),
                    request -> finalAnswer(ScriptedChatModel.lastToolResult(request)
                            .map(ToolExecutionResultMessage::text).orElse("none")).apply(request));

            String answer = investigate("{}");

            assertThat(answer).contains("tool arguments could not be parsed");
            assertThat(ctx.used().get()).isEqualTo(1);
        } finally {
            contexts.remove(INCIDENT_ID);
        }
    }

    @Test
    void unknownToolNameReturnsAnErrorResultListingTheTools() {
        model.enqueue(
                toolCall("restartService", "{\"service\": \"checkout-api\"}"),
                request -> finalAnswer(ScriptedChatModel.lastToolResult(request)
                        .map(ToolExecutionResultMessage::text).orElse("none")).apply(request));

        String answer = investigate("{}");

        assertThat(answer).contains("unknown tool").contains("getMetric").contains("findRunbook")
                .doesNotContain("restartService");
    }

    @Test
    void evictingChatMemoryForgetsTheConversation() {
        investigate("{}");
        assertThat(agent.evictChatMemory(INCIDENT_ID)).isTrue();
        assertThat(agent.evictChatMemory(INCIDENT_ID)).isFalse();
    }

    private String investigate(String incidentJson) {
        return agent.investigate(INCIDENT_ID, incidentJson, WINDOW_START, WINDOW_END, ALERT_EVIDENCE_ID,
                promptInputs.reportSchema());
    }
}
