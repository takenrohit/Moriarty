package dev.investigator.ratelimit;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Bucket sizes under {@code investigator.rate-limit} (Req 6.6). Each bucket holds at most its limit and
 * refills continuously at the limit per period, so a full bucket allows a burst of the limit.
 *
 * @param toolCallsPerMinute tool calls per minute by integration; an integration without an entry gets
 *     {@value #DEFAULT_TOOL_CALLS_PER_MINUTE}
 * @param investigationsPerServicePerHour new investigations per affected service per hour
 */
@Validated
@ConfigurationProperties("investigator.rate-limit")
public record RateLimitProperties(
        Map<String, @Min(1) Integer> toolCallsPerMinute,
        @Min(1) @DefaultValue("20") int investigationsPerServicePerHour) {

    public static final int DEFAULT_TOOL_CALLS_PER_MINUTE = 120;
    public static final Duration TOOL_PERIOD = Duration.ofMinutes(1);
    public static final Duration INVESTIGATION_PERIOD = Duration.ofHours(1);

    public RateLimitProperties {
        toolCallsPerMinute = toolCallsPerMinute == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(toolCallsPerMinute));
    }

    /** The per-minute limit of {@code integration}. */
    public int toolCallsPerMinute(String integration) {
        Integer limit = toolCallsPerMinute.get(integration);
        return limit == null ? DEFAULT_TOOL_CALLS_PER_MINUTE : limit;
    }
}
