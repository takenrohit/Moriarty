package dev.investigator.testsupport;

import java.util.SplittableRandom;
import java.util.stream.LongStream;
import org.junit.jupiter.api.function.ThrowingConsumer;

/**
 * Seed source and failure reporting for property tests (design "Testing Strategy", CP-29).
 *
 * <p>Each property is one {@code @ParameterizedTest(name = "seed={0}")} method with
 * {@code @MethodSource("dev.investigator.testsupport.PropertySeeds#seeds")} whose body runs inside
 * {@link #check(long, ThrowingConsumer)}.
 *
 * <p>System properties:
 * <ul>
 *   <li>{@code pbt.seed}: master seed for the generated seed list (default {@value #DEFAULT_MASTER_SEED});</li>
 *   <li>{@code pbt.cases}: number of cases, never fewer than {@value #MIN_CASES};</li>
 *   <li>{@code pbt.replay}: run only this one seed, for replaying a reported failure.</li>
 * </ul>
 */
public final class PropertySeeds {

    public static final long DEFAULT_MASTER_SEED = 20261123L;
    public static final int MIN_CASES = 100;

    private PropertySeeds() {
    }

    /** Seeds for one property: the replay seed alone, or at least {@value #MIN_CASES} generated seeds. */
    public static LongStream seeds() {
        Long replay = Long.getLong("pbt.replay");
        if (replay != null) {
            return LongStream.of(replay);
        }
        int cases = Math.max(MIN_CASES, Integer.getInteger("pbt.cases", MIN_CASES));
        SplittableRandom master = new SplittableRandom(Long.getLong("pbt.seed", DEFAULT_MASTER_SEED));
        return LongStream.generate(master::nextLong).limit(cases);
    }

    /**
     * Runs {@code body} with a {@link SplittableRandom} built from {@code seed}. Any failure is rethrown as
     * an {@link AssertionError} whose message starts with {@code seed=<seed>} and gives the replay command;
     * the original failure is the cause.
     */
    public static void check(long seed, ThrowingConsumer<SplittableRandom> body) {
        try {
            body.accept(new SplittableRandom(seed));
        } catch (Throwable failure) {
            throw new AssertionError("seed=" + seed + " failed; replay with: .\\mvnw.cmd test -Dtest=" + testClassName()
                    + " -Dpbt.replay=" + seed + " (./mvnw on Linux/macOS): " + failure, failure);
        }
    }

    /** Simple name of the calling test class, so the replay command can be copied as is. */
    private static String testClassName() {
        return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .walk(frames -> frames
                        .map(StackWalker.StackFrame::getDeclaringClass)
                        .filter(type -> type != PropertySeeds.class)
                        .findFirst()
                        .map(Class::getSimpleName)
                        .orElse("<TestClass>"));
    }
}
