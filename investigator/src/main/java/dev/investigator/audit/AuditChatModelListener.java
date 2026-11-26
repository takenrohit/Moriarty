package dev.investigator.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.investigator.config.JacksonConfig;
import dev.investigator.investigation.InvestigationObservability;
import dev.investigator.logging.LogFields;
import dev.investigator.observability.InvestigatorMetrics;
import dev.investigator.store.jdbc.PgValues;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.observation.Observation;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Records each LLM exchange of the production Chat_Model through the {@link AuditService} (Req 12.5).
 * Registered on the OpenAI model in {@code LlmConfig}.
 *
 * <p>Only the request messages (with the model name) and the response message are recorded, never the
 * API key or HTTP headers, which the listener does not see. The Incident_ID comes from the MDC of the
 * agent thread. The listener never throws.
 */
@Component
public class AuditChatModelListener implements ChatModelListener {

    private static final String STARTED = AuditChatModelListener.class.getName() + ".started";
    private static final String OBSERVATION = AuditChatModelListener.class.getName() + ".observation";

    private final AuditService audit;
    private final InvestigationObservability observability;
    private final InvestigatorMetrics metrics;
    private final ObjectMapper codec = JacksonConfig.jsonCodec();

    public AuditChatModelListener(AuditService audit, InvestigationObservability observability,
            InvestigatorMetrics metrics) {
        this.audit = audit;
        this.observability = observability;
        this.metrics = metrics;
    }

    @Override
    public void onRequest(ChatModelRequestContext context) {
        context.attributes().put(STARTED, System.nanoTime());
        // llm.chat span, a child of the investigation span in scope on the agent thread (Req 1.3).
        guarded(() -> context.attributes().put(OBSERVATION,
                observability.llmChat(requestModel(context.chatRequest())).start()));
    }

    @Override
    public void onResponse(ChatModelResponseContext context) {
        ChatResponse response = context.chatResponse();
        ChatResponseMetadata metadata = response == null ? null : response.metadata();
        TokenUsage usage = metadata == null ? null : metadata.tokenUsage();
        String model = metadata != null && metadata.modelName() != null
                ? metadata.modelName() : requestModel(context.chatRequest());
        guarded(() -> {
            if (context.attributes().get(OBSERVATION) instanceof Observation observation) {
                observation.lowCardinalityKeyValue(InvestigationObservability.MODEL,
                        model == null ? InvestigationObservability.UNKNOWN : model);
                observation.lowCardinalityKeyValue(InvestigationObservability.STATUS, "success");
                Integer total = usage == null ? null : usage.totalTokenCount();
                observation.highCardinalityKeyValue(InvestigationObservability.TOKENS,
                        total == null ? InvestigationObservability.UNKNOWN : total.toString());
                observation.stop();
            }
        });
        guarded(() -> metrics.llmTokens(model, usage == null ? null : usage.inputTokenCount(),
                usage == null ? null : usage.outputTokenCount()));
        guarded(() -> {
            String responseJson = response == null || response.aiMessage() == null
                    ? null : ChatMessageSerializer.messageToJson(response.aiMessage());
            audit.recordLlmExchange(new AuditService.LlmExchange(MDC.get(LogFields.INCIDENT_ID), model,
                    requestJson(context.chatRequest()), responseJson,
                    usage == null ? null : usage.inputTokenCount(), usage == null ? null : usage.outputTokenCount(),
                    latencyMs(context.attributes())));
        });
    }

    @Override
    public void onError(ChatModelErrorContext context) {
        guarded(() -> {
            if (context.attributes().get(OBSERVATION) instanceof Observation observation) {
                observation.lowCardinalityKeyValue(InvestigationObservability.STATUS, "error");
                if (context.error() != null) {
                    observation.error(context.error());
                }
                observation.stop();
            }
        });
        guarded(() -> {
            Map<String, Object> error = new LinkedHashMap<>();
            // The class name only: provider error messages can echo request details.
            error.put("error", context.error() == null ? null : context.error().getClass().getName());
            audit.recordLlmExchange(new AuditService.LlmExchange(MDC.get(LogFields.INCIDENT_ID),
                    requestModel(context.chatRequest()), requestJson(context.chatRequest()),
                    PgValues.json(codec, error), null, null, latencyMs(context.attributes())));
        });
    }

    private String requestJson(ChatRequest request) throws IOException {
        if (request == null) {
            return null;
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("model", request.modelName());
        json.put("messages", codec.readTree(ChatMessageSerializer.messagesToJson(request.messages())));
        return PgValues.json(codec, json);
    }

    private static String requestModel(ChatRequest request) {
        return request == null ? null : request.modelName();
    }

    private static long latencyMs(Map<Object, Object> attributes) {
        return attributes.get(STARTED) instanceof Long started ? (System.nanoTime() - started) / 1_000_000 : 0L;
    }

    private static void guarded(ThrowingRunnable body) {
        try {
            body.run();
        } catch (Exception e) {
            LoggerFactory.getLogger(AuditChatModelListener.class)
                    .warn("LLM exchange not audited error={}", e.getClass().getSimpleName());
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
