package dev.investigator.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings for the OpenAI-compatible Chat_Model, bound from {@code investigator.llm.*}, which
 * {@code application.yml} fills from {@code LLM_BASE_URL}, {@code LLM_API_KEY}, {@code LLM_MODEL} and
 * {@code LLM_JSON_MODE} (Req 2.1, 13.7, 13.8).
 *
 * <p>{@link #toString()} masks the API key (Req 2.4).
 *
 * @param enabled whether the production Chat_Model bean is created; tests set it to {@code false}
 * @param baseUrl OpenAI-compatible endpoint
 * @param apiKey LLM API key; never logged
 * @param model model name
 * @param jsonMode request a JSON object response format
 * @param temperature sampling temperature
 * @param requestTimeout timeout for one LLM HTTP request
 */
@Validated
@ConfigurationProperties("investigator.llm")
public record LlmProperties(
        @DefaultValue("true") boolean enabled,
        @NotBlank @DefaultValue("https://api.openai.com/v1") String baseUrl,
        String apiKey,
        @NotBlank @DefaultValue("gpt-4o-mini") String model,
        @DefaultValue("true") boolean jsonMode,
        @NotNull @DecimalMin("0.0") @DecimalMax("2.0") @DefaultValue("0.0") Double temperature,
        @NotNull @DurationMin(seconds = 1) @DefaultValue("60s") Duration requestTimeout) {

    static final String MASK = "****";

    /** Returns all settings with the API key replaced by {@code ****}, or empty when no key is set. */
    @Override
    public String toString() {
        String maskedKey = apiKey == null || apiKey.isEmpty() ? "" : MASK;
        return "LlmProperties[enabled=" + enabled
                + ", baseUrl=" + baseUrl
                + ", apiKey=" + maskedKey
                + ", model=" + model
                + ", jsonMode=" + jsonMode
                + ", temperature=" + temperature
                + ", requestTimeout=" + requestTimeout
                + "]";
    }
}
