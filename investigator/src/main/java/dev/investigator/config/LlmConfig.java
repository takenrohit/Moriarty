package dev.investigator.config;

import dev.langchain4j.model.bedrock.BedrockChatModel;
import dev.langchain4j.model.bedrock.BedrockChatRequestParameters;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.token.credentials.StaticTokenProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.auth.scheme.BedrockRuntimeAuthSchemeProvider;

/**
 * Builds the production Chat_Model from {@link LlmProperties}: a LangChain4j {@link OpenAiChatModel}
 * for {@code openai} (Req 13.7) or a {@link BedrockChatModel} for {@code bedrock} (Req 11).
 *
 * <p>Active unless {@code investigator.llm.enabled=false}; tests disable it and supply a scripted
 * model. Request and response logging stay off, so no API key reaches the log (Req 2.4, 11.10). Every
 * {@link ChatModelListener} bean (audit, metrics and span listeners, Req 12.5) is registered on the model.
 * Building either model makes no network call.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "investigator.llm.enabled", havingValue = "true", matchIfMissing = true)
public class LlmConfig {

    /** Smithy bearer scheme {@code smithy.api#httpBearerAuth}, named by the part after {@code #}. */
    static final String BEARER_AUTH_SCHEME = "httpBearerAuth";

    @Bean
    ChatModel chatModel(LlmProperties llm, ObjectProvider<ChatModelListener> listeners) {
        List<ChatModelListener> registered = listeners.orderedStream().toList();
        return llm.isBedrock() ? bedrock(llm, registered) : openAi(llm, registered);
    }

    private static ChatModel openAi(LlmProperties llm, List<ChatModelListener> listeners) {
        if (!StringUtils.hasText(llm.apiKey())) {
            // Req 2.6, 11.6: fail startup naming the variable, without echoing any value.
            throw new IllegalStateException(
                    "LLM_API_KEY is not set. Set it in the environment or .env file, set LLM_PROVIDER=bedrock,"
                            + " or set investigator.llm.enabled=false and provide another ChatModel bean.");
        }
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .baseUrl(llm.baseUrl())
                .apiKey(llm.apiKey())
                .modelName(llm.model())
                .temperature(llm.temperature())
                .timeout(llm.requestTimeout())
                .maxRetries(llm.maxRetries())
                .listeners(listeners);
        if (llm.jsonMode()) {
            // ResponseFormat.JSON without a schema is sent as {"type":"json_object"} (Req 13.8).
            builder.responseFormat(ResponseFormat.JSON);
        }
        return builder.build();
    }

    /** Req 11.3 to 11.5, 11.8, 11.9, 11.11, 11.12: no JSON response format is sent. */
    private static ChatModel bedrock(LlmProperties llm, List<ChatModelListener> listeners) {
        if (!StringUtils.hasText(llm.model())) {
            throw new IllegalStateException("LLM_MODEL is not set; set it to a Bedrock model ID or inference profile ID.");
        }
        Region region = region(llm.bedrock().region());
        BedrockChatModel.Builder builder = BedrockChatModel.builder()
                .modelId(llm.model())
                .timeout(llm.requestTimeout())
                .maxRetries(llm.maxRetries())
                .listeners(listeners)
                .defaultRequestParameters(BedrockChatRequestParameters.builder()
                        .temperature(llm.temperature())
                        .maxOutputTokens(llm.maxOutputTokens())
                        .build());
        String key = llm.bedrock().apiKey();
        if (StringUtils.hasText(key)) {
            // Bearer auth with the configured Bedrock_API_Key; the explicit token provider keeps the
            // configured value authoritative over the SDK's own AWS_BEARER_TOKEN_BEDROCK lookup.
            builder.client(BedrockRuntimeClient.builder()
                    .region(region)
                    .tokenProvider(StaticTokenProvider.create(() -> key))
                    .authSchemeProvider(BedrockRuntimeAuthSchemeProvider.defaultProvider(List.of(BEARER_AUTH_SCHEME)))
                    .build());
        } else {
            // Default AWS credential chain (AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY, AWS_PROFILE, ...).
            builder.region(region);
        }
        return builder.build();
    }

    /** Returns the region when the SDK knows it; otherwise fails startup naming {@code AWS_REGION}. */
    static Region region(String name) {
        if (StringUtils.hasText(name)) {
            for (Region known : Region.regions()) {
                if (known.id().equals(name)) {
                    return known;
                }
            }
        }
        throw new IllegalStateException("AWS_REGION '" + (name == null ? "" : name)
                + "' is not a valid AWS region name, for example us-east-1.");
    }
}
