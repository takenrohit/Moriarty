package dev.investigator.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings for the Chat_Model, bound from {@code investigator.llm.*}, which {@code application.yml} fills
 * from {@code LLM_PROVIDER}, {@code LLM_BASE_URL}, {@code LLM_API_KEY}, {@code LLM_MODEL},
 * {@code LLM_JSON_MODE}, {@code LLM_MAX_OUTPUT_TOKENS}, {@code AWS_REGION} and
 * {@code AWS_BEARER_TOKEN_BEDROCK} (Req 2.1, 11.1, 13.7, 13.8).
 *
 * <p>{@link #toString()} masks both API keys (Req 2.4, 11.10).
 *
 * @param enabled whether the production Chat_Model bean is created; tests set it to {@code false}
 * @param provider {@code openai} (OpenAI-compatible endpoint) or {@code bedrock} (Amazon Bedrock)
 * @param baseUrl OpenAI-compatible endpoint; {@code openai} only
 * @param apiKey LLM API key; required only for {@code openai}; never logged
 * @param model model name, or the Bedrock model ID or inference profile ID
 * @param jsonMode request a JSON object response format; {@code openai} only
 * @param temperature sampling temperature
 * @param requestTimeout timeout for one LLM HTTP request
 * @param maxRetries retries for one LLM HTTP request on retriable errors such as 429 or 5xx; LangChain4j
 *     waits 0.5 s × 1.5^n before retry n, so 10 retries cover about one minute of rate limiting
 * @param maxOutputTokens maximum output tokens per request; {@code bedrock} only
 * @param bedrock Amazon Bedrock settings
 */
@Validated
@ConfigurationProperties("investigator.llm")
public record LlmProperties(
        @DefaultValue("true") boolean enabled,
        @NotNull @Pattern(regexp = "openai|bedrock", message = "LLM_PROVIDER must be openai or bedrock")
                @DefaultValue("openai") String provider,
        @NotBlank @DefaultValue("https://api.openai.com/v1") String baseUrl,
        String apiKey,
        @NotBlank(message = "LLM_MODEL must not be blank") @DefaultValue("gpt-4o-mini") String model,
        @DefaultValue("true") boolean jsonMode,
        @NotNull @DecimalMin("0.0") @DecimalMax("2.0") @DefaultValue("0.0") Double temperature,
        @NotNull @DurationMin(seconds = 1) @DefaultValue("60s") Duration requestTimeout,
        @Min(0) @Max(20) @DefaultValue("2") int maxRetries,
        @Min(1) @DefaultValue("4096") int maxOutputTokens,
        @NotNull @DefaultValue Bedrock bedrock) {

    static final String MASK = "****";
    static final String OPENAI = "openai";
    static final String BEDROCK = "bedrock";

    /** True when the Bedrock provider is selected. */
    public boolean isBedrock() {
        return BEDROCK.equals(provider);
    }

    /** Returns all settings with the API keys replaced by {@code ****}, or empty when no key is set. */
    @Override
    public String toString() {
        return "LlmProperties[enabled=" + enabled
                + ", provider=" + provider
                + ", baseUrl=" + baseUrl
                + ", apiKey=" + mask(apiKey)
                + ", model=" + model
                + ", jsonMode=" + jsonMode
                + ", temperature=" + temperature
                + ", requestTimeout=" + requestTimeout
                + ", maxRetries=" + maxRetries
                + ", maxOutputTokens=" + maxOutputTokens
                + ", bedrock=" + bedrock
                + "]";
    }

    static String mask(String key) {
        return key == null || key.isEmpty() ? "" : MASK;
    }

    /**
     * Amazon Bedrock settings.
     *
     * @param region AWS region name ({@code AWS_REGION}); checked against the SDK's known regions at startup
     * @param apiKey Bedrock_API_Key ({@code AWS_BEARER_TOKEN_BEDROCK}), sent as a bearer token; when blank the
     *     default AWS credential chain is used; never logged
     */
    public record Bedrock(@DefaultValue("us-east-1") String region, String apiKey) {

        /** Returns the settings with the API key replaced by {@code ****}, or empty when no key is set. */
        @Override
        public String toString() {
            return "Bedrock[region=" + region + ", apiKey=" + mask(apiKey) + "]";
        }
    }
}
