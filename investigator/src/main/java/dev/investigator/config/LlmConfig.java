package dev.investigator.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Builds the production Chat_Model: a LangChain4j OpenAI chat model configured from
 * {@link LlmProperties} (Req 13.7).
 *
 * <p>Active unless {@code investigator.llm.enabled=false}; tests disable it and supply a scripted
 * model. Request and response logging stay off, so the API key never reaches the log (Req 2.4). Every
 * {@link ChatModelListener} bean (the audit listener, Req 12.5) is registered on the model.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "investigator.llm.enabled", havingValue = "true", matchIfMissing = true)
public class LlmConfig {

    @Bean
    ChatModel chatModel(LlmProperties llm, ObjectProvider<ChatModelListener> listeners) {
        if (!StringUtils.hasText(llm.apiKey())) {
            // Req 2.6: fail startup naming the variable, without echoing any value.
            throw new IllegalStateException(
                    "LLM_API_KEY is not set. Set it in the environment or .env file,"
                            + " or set investigator.llm.enabled=false and provide another ChatModel bean.");
        }
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .baseUrl(llm.baseUrl())
                .apiKey(llm.apiKey())
                .modelName(llm.model())
                .temperature(llm.temperature())
                .timeout(llm.requestTimeout())
                .maxRetries(llm.maxRetries())
                .listeners(listeners.orderedStream().toList());
        if (llm.jsonMode()) {
            // ResponseFormat.JSON without a schema is sent as {"type":"json_object"} (Req 13.8).
            builder.responseFormat(ResponseFormat.JSON);
        }
        return builder.build();
    }
}
