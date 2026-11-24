package dev.investigator.investigation;

import dev.investigator.config.InvestigatorProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Computes the Investigation_Window {@code [detectedAt − lookBack, detectedAt + lookAhead]} (Req 7.1).
 *
 * <p>{@link InvestigatorProperties.Window} already rejects a look-back plus look-ahead longer than the
 * maximum span at startup (Req 7.3), so with configured values the window satisfies
 * {@code start ≤ detectedAt ≤ end} and {@code end − start ≤ maxSpan} (Req 7.4).
 */
public final class InvestigationWindowCalculator {

    private InvestigationWindowCalculator() {
    }

    /** An Investigation_Window; both bounds are inclusive and {@code start ≤ end}. */
    public record Window(Instant start, Instant end) {

        public Window {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            if (start.isAfter(end)) {
                throw new IllegalArgumentException("window start must not be after its end");
            }
        }

        public Duration span() {
            return Duration.between(start, end);
        }
    }

    /**
     * @throws IllegalArgumentException if {@code lookBack} or {@code lookAhead} is negative
     */
    public static Window window(Instant detectedAt, Duration lookBack, Duration lookAhead) {
        Objects.requireNonNull(detectedAt, "detectedAt");
        Objects.requireNonNull(lookBack, "lookBack");
        Objects.requireNonNull(lookAhead, "lookAhead");
        if (lookBack.isNegative() || lookAhead.isNegative()) {
            throw new IllegalArgumentException("look-back and look-ahead must not be negative");
        }
        return new Window(detectedAt.minus(lookBack), detectedAt.plus(lookAhead));
    }

    /** The window for {@code detectedAt} with the configured {@code investigator.window.*} durations. */
    public static Window window(Instant detectedAt, InvestigatorProperties.Window config) {
        Objects.requireNonNull(config, "config");
        return window(detectedAt, config.lookBack(), config.lookAhead());
    }
}
