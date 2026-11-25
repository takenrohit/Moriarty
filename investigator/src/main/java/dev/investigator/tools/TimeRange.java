package dev.investigator.tools;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * A time range after the {@link ToolGuard} applied the Investigation_Window: {@code start < end},
 * both inside the window (Req 11.3, 11.5).
 *
 * @param clamped true when the requested range was shortened to fit
 */
public record TimeRange(Instant start, Instant end, boolean clamped) {

    public TimeRange {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("start must be before end");
        }
    }

    public Duration span() {
        return Duration.between(start, end);
    }
}
