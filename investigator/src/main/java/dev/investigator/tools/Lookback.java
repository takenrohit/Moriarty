package dev.investigator.tools;

import java.time.Instant;
import java.util.Objects;

/**
 * The look-back range of {@code getRecentChanges} after the {@link ToolGuard} applied the 24 h cap:
 * {@code [until − hours, until]} with {@code until} the Investigation_Window end (Req 11.8, 11.9).
 *
 * @param hours the applied look-back in hours, from 1 to the configured maximum
 * @param clamped true when the requested look-back was larger than the maximum
 */
public record Lookback(int hours, Instant since, Instant until, boolean clamped) {

    public Lookback {
        Objects.requireNonNull(since, "since");
        Objects.requireNonNull(until, "until");
        if (hours < 1) {
            throw new IllegalArgumentException("hours must be at least 1");
        }
        if (!since.isBefore(until)) {
            throw new IllegalArgumentException("since must be before until");
        }
    }
}
