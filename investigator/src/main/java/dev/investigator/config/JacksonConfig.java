package dev.investigator.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * JSON_Codec settings for the Spring {@link ObjectMapper}.
 *
 * <p>{@code Instant} values are written as ISO-8601 UTC strings ending in {@code Z} (Req 3.4), and
 * unknown properties in HTTP input are ignored (Req 4.10). The strict mapper used for model drafts is
 * created separately by the report parser.
 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

    /** Registered by Spring Boot's Jackson auto-configuration like any other {@link Module} bean. */
    @Bean
    Module javaTimeModule() {
        return new JavaTimeModule();
    }

    @Bean
    Jackson2ObjectMapperBuilderCustomizer jsonCodecCustomizer() {
        return JacksonConfig::applyCodecSettings;
    }

    /**
     * Builds a mapper with the same settings outside a Spring context, for example in tests.
     */
    public static ObjectMapper jsonCodec() {
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
        builder.modulesToInstall(new JavaTimeModule());
        applyCodecSettings(builder);
        return builder.build();
    }

    private static void applyCodecSettings(Jackson2ObjectMapperBuilder builder) {
        builder.featuresToDisable(
                SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,
                DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
