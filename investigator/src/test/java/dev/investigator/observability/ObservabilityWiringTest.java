package dev.investigator.observability;

import static dev.investigator.testsupport.ScriptedChatModel.finalAnswer;
import static dev.investigator.testsupport.ScriptedChatModel.toolCall;
import static org.assertj.core.api.Assertions.assertThat;

import dev.investigator.audit.AuditChatModelListener;
import dev.investigator.domain.Incident;
import dev.investigator.domain.IncidentStatus;
import dev.investigator.domain.InvestigationStatus;
import dev.investigator.domain.Severity;
import dev.investigator.investigation.InvestigationObservability;
import dev.investigator.investigation.InvestigationOrchestrator;
import dev.investigator.store.IncidentRepository;
import dev.investigator.store.ReportRepository;
import dev.investigator.testsupport.InvestigatorSpringBootTest;
import dev.investigator.testsupport.ScriptedChatModel;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * One scripted investigation with a tool call records the investigation, tool and report meters and an
 * {@code investigation} observation with a {@code tool.getMetric} child opened on the agent thread; an
 * LLM response through the listener records token counters and an {@code llm.chat} observation
 * (Req 1.1 to 1.3, 2.1 to 2.4).
 */
@InvestigatorSpringBootTest
class ObservabilityWiringTest {

    private static final Instant DETECTED_AT = Instant.parse("2026-11-23T14:40:00Z");
    private static final String METRIC_ARGS = "{\"service\":\"checkout-api\",\"promql\":\"up\","
            + "\"start\":\"2026-11-23T14:00:00Z\",\"end\":\"2026-11-23T14:45:00Z\"}";

    /** Stopped observation contexts; the handler is added once to the shared context's registry. */
    private static final Queue<Observation.Context> STOPPED = new ConcurrentLinkedQueue<>();
    private static volatile boolean handlerAdded;

    @Autowired InvestigationOrchestrator orchestrator;
    @Autowired ScriptedChatModel model;
    @Autowired IncidentRepository incidents;
    @Autowired ReportRepository reports;
    @Autowired MeterRegistry meters;
    @Autowired ObservationRegistry observations;
    @Autowired AuditChatModelListener listener;

    @BeforeEach
    void setUp() {
        model.reset();
        synchronized (ObservabilityWiringTest.class) {
            if (!handlerAdded) {
                observations.observationConfig().observationHandler(new ObservationHandler<>() {
                    @Override
                    public boolean supportsContext(Observation.Context context) {
                        return true;
                    }

                    @Override
                    public void onStop(Observation.Context context) {
                        STOPPED.add(context);
                    }
                });
                handlerAdded = true;
            }
        }
    }

    @Test
    void investigationRecordsMetersAndNestedObservations() throws Exception {
        Incident incident = newIncident();
        String alert = "ev-" + incident.id() + "-1";
        double investigations = timerCount(InvestigatorMetrics.INVESTIGATION_DURATION, "status", "COMPLETED");
        double toolCalls = counter(InvestigatorMetrics.TOOL_CALLS, "tool", "getMetric", "status", "success");
        double toolLatency = timerCount(InvestigatorMetrics.TOOL_LATENCY, "tool", "getMetric");
        double validReports = counter(InvestigatorMetrics.REPORT_GENERATED, "validation_status", "valid");
        model.enqueue(toolCall("getMetric", METRIC_ARGS), finalAnswer(draft(incident.id(), alert)));

        orchestrator.start(incident);

        Observation.Context investigation = await(() -> STOPPED.stream()
                .filter(c -> InvestigationObservability.INVESTIGATION.equals(c.getName()))
                .filter(c -> incident.id().equals(value(c.getHighCardinalityKeyValue("incident.id"))))
                .findFirst());
        assertThat(reports.find(incident.id())).get()
                .satisfies(r -> assertThat(r.status()).isEqualTo(InvestigationStatus.COMPLETED));
        assertThat(value(investigation.getLowCardinalityKeyValue("service"))).isEqualTo("checkout-api");
        assertThat(value(investigation.getLowCardinalityKeyValue("severity"))).isEqualTo("CRITICAL");
        assertThat(value(investigation.getLowCardinalityKeyValue("status"))).isEqualTo("COMPLETED");
        assertThat(STOPPED).anySatisfy(c -> {
            assertThat(c.getName()).isEqualTo("tool.getMetric");
            assertThat(c.getParentObservation()).isNotNull();
            assertThat(c.getParentObservation().getContextView()).isSameAs(investigation);
        });

        assertThat(timerCount(InvestigatorMetrics.INVESTIGATION_DURATION, "status", "COMPLETED"))
                .isGreaterThanOrEqualTo(investigations + 1);
        assertThat(counter(InvestigatorMetrics.TOOL_CALLS, "tool", "getMetric", "status", "success"))
                .isGreaterThanOrEqualTo(toolCalls + 1);
        assertThat(timerCount(InvestigatorMetrics.TOOL_LATENCY, "tool", "getMetric"))
                .isGreaterThanOrEqualTo(toolLatency + 1);
        assertThat(counter(InvestigatorMetrics.REPORT_GENERATED, "validation_status", "valid"))
                .isGreaterThanOrEqualTo(validReports + 1);
    }

    @Test
    void llmResponseRecordsTokenCountersAndAnLlmChatObservation() {
        String modelName = "wiring-test-model";
        double input = counter(InvestigatorMetrics.LLM_TOKENS, "direction", "input", "model", modelName);
        double output = counter(InvestigatorMetrics.LLM_TOKENS, "direction", "output", "model", modelName);
        ChatRequest request = ChatRequest.builder().messages(UserMessage.from("hi")).modelName(modelName).build();
        Map<Object, Object> attributes = new HashMap<>();

        listener.onRequest(new ChatModelRequestContext(request, ModelProvider.OTHER, attributes));
        ChatResponse response = ChatResponse.builder().aiMessage(AiMessage.from("{}")).modelName(modelName)
                .tokenUsage(new TokenUsage(7, 3)).build();
        listener.onResponse(new ChatModelResponseContext(response, request, ModelProvider.OTHER, attributes));

        assertThat(counter(InvestigatorMetrics.LLM_TOKENS, "direction", "input", "model", modelName))
                .isEqualTo(input + 7);
        assertThat(counter(InvestigatorMetrics.LLM_TOKENS, "direction", "output", "model", modelName))
                .isEqualTo(output + 3);
        assertThat(STOPPED).anySatisfy(c -> {
            assertThat(c.getName()).isEqualTo(InvestigationObservability.LLM_CHAT);
            assertThat(value(c.getLowCardinalityKeyValue("model"))).isEqualTo(modelName);
            assertThat(value(c.getHighCardinalityKeyValue("tokens"))).isEqualTo("10");
        });
    }

    private double counter(String name, String... tags) {
        Counter counter = meters.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    private double timerCount(String name, String... tags) {
        Timer timer = meters.find(name).tags(tags).timer();
        return timer == null ? 0 : timer.count();
    }

    private static String value(KeyValue keyValue) {
        return keyValue == null ? null : keyValue.getValue();
    }

    private static <T> T await(Supplier<Optional<T>> probe) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            Optional<T> found = probe.get();
            if (found.isPresent()) {
                return found.get();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("condition not met within 10 s");
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

    /** A valid draft citing only the alert Evidence. */
    private static String draft(String incidentId, String alert) {
        return """
                {
                  "incidentId": "%1$s",
                  "summary": "Checkout errors rose after payment-provider timeouts.",
                  "timeline": [
                    { "at": "2026-11-23T14:40:00Z", "description": "Alert fired", "evidenceIds": ["%2$s"] }
                  ],
                  "hypotheses": [
                    { "statement": "Provider timeouts", "confidence": "MEDIUM", "supportingEvidenceIds": ["%2$s"],
                      "contradictingEvidenceIds": [], "establishedCause": false }
                  ],
                  "actions": [
                    { "order": 1, "type": "INVESTIGATE", "description": "Check provider status",
                      "requiresApproval": false, "evidenceIds": ["%2$s"] }
                  ],
                  "openQuestions": []
                }
                """.formatted(incidentId, alert);
    }
}
