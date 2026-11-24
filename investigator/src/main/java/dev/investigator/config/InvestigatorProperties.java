package dev.investigator.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Investigator_Config: typed settings under the {@code investigator.*} prefix.
 *
 * <p>Defaults match {@code application.yml}, so the record tree is complete even when bound from an
 * empty environment (for example in an {@code ApplicationContextRunner}). Invalid values fail startup
 * with a binding error that names the property (Req 2.5). The LLM settings live in
 * {@link LlmProperties}, so this record never holds the API key.
 *
 * @param displayTimezone IANA zone used to display times in reports (Req 2.7)
 * @param window investigation window around {@code detectedAt} (Req 7.2, 7.3)
 * @param budget tool-call budget and overall timeout per investigation
 * @param tools tool guard limits and summary sizes
 * @param agent agent chat memory settings
 * @param services Allow_List: service name to its dependencies
 * @param mock mock integration settings
 */
@Validated
@ConfigurationProperties("investigator")
public record InvestigatorProperties(
        @NotNull @DefaultValue("Asia/Kolkata") ZoneId displayTimezone,
        @NotNull @Valid @DefaultValue Window window,
        @NotNull @Valid @DefaultValue Budget budget,
        @NotNull @Valid @DefaultValue Tools tools,
        @NotNull @Valid @DefaultValue Agent agent,
        Map<String, @Valid AllowedService> services,
        @NotNull @Valid @DefaultValue Mock mock) {

    public InvestigatorProperties {
        services = services == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(services));
    }

    /**
     * Investigation window: {@code [detectedAt − lookBack, detectedAt + lookAhead]}.
     *
     * <p>The compact constructor rejects a look-back plus look-ahead longer than {@code maxSpan}, with a
     * message naming the {@code investigator.window.*} properties (Req 7.3).
     */
    public record Window(
            @NotNull @DurationMin(seconds = 0) @DefaultValue("60m") Duration lookBack,
            @NotNull @DurationMin(seconds = 0) @DefaultValue("10m") Duration lookAhead,
            @NotNull @DurationMin(seconds = 1) @DefaultValue("6h") Duration maxSpan) {

        public Window {
            if (lookBack != null && lookAhead != null && maxSpan != null
                    && lookBack.plus(lookAhead).compareTo(maxSpan) > 0) {
                throw new IllegalArgumentException(
                        "investigator.window.look-back (" + lookBack + ") plus investigator.window.look-ahead ("
                                + lookAhead + ") must not exceed investigator.window.max-span (" + maxSpan + ")");
            }
        }
    }

    /** Tool_Call_Budget and the overall investigation timeout. */
    public record Budget(
            @Min(1) @DefaultValue("15") int maxToolCalls,
            @NotNull @DurationMin(seconds = 1) @DefaultValue("3m") Duration timeout) {
    }

    /** Tool_Guard limits and compact summary sizes. */
    public record Tools(
            @Min(1) @DefaultValue("1000") int maxQueryLength,
            @Min(1) @DefaultValue("200") int maxSymptomLength,
            @Min(1) @DefaultValue("24") int maxLookbackHours,
            @NotNull @DurationMin(seconds = 1) @DefaultValue("6h") Duration maxSpan,
            @Min(256) @DefaultValue("4096") int maxResultBytes,
            @Min(2) @DefaultValue("300") int maxMetricPoints,
            @NotNull @DurationMin(seconds = 1) @DefaultValue("15s") Duration minMetricStep,
            @Min(1) @DefaultValue("500") int logFetchLimit,
            @Min(1) @DefaultValue("5") int logTopMessages,
            @Min(1) @DefaultValue("50") int traceLimit,
            @Min(1) @DefaultValue("3") int traceExamples,
            @NotNull @DurationMin(millis = 1) @DefaultValue("2s") Duration slowTraceThreshold,
            @Min(0) @DefaultValue("20") int maxChangedFiles) {
    }

    /** Investigator_Agent settings. */
    public record Agent(@Min(2) @DefaultValue("64") int memoryMaxMessages) {
    }

    /** One Allow_List entry: the services this service calls. */
    public record AllowedService(List<String> dependencies) {

        public AllowedService {
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        }
    }

    /**
     * Mock integration settings.
     *
     * @param defaultAnchor Scenario_Anchor used when no investigation scope is active (the demo
     *     {@code detectedAt})
     */
    public record Mock(@NotNull @DefaultValue("2026-11-23T14:40:00Z") Instant defaultAnchor) {
    }
}
