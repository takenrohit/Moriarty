package dev.investigator.alert;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Random;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Generates Incident_IDs: {@code inc-} followed by 12 lowercase hexadecimal characters (48 random bits).
 *
 * <p>Uniqueness within the Incident_Store (Req 5.3) is enforced by the caller's {@code exists} check:
 * a candidate that already exists is discarded and a new one is drawn.
 */
@Component
public class IncidentIdGenerator {

    /** Pattern every generated ID matches. */
    public static final Pattern ID_PATTERN = Pattern.compile("inc-[0-9a-f]{12}");

    /** Upper bound on draws per call, so a broken random source cannot loop forever. */
    static final int MAX_ATTEMPTS = 100;

    private static final int RANDOM_BYTES = 6;
    private static final HexFormat HEX = HexFormat.of();

    private final Random random;

    @Autowired
    public IncidentIdGenerator() {
        this(new SecureRandom());
    }

    /** Uses the given random source; tests pass a deterministic or repeating one. */
    public IncidentIdGenerator(Random random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * Returns a new ID for which {@code exists} is false.
     *
     * @throws IllegalStateException if {@value #MAX_ATTEMPTS} consecutive candidates already exist
     */
    public String next(Predicate<String> exists) {
        Objects.requireNonNull(exists, "exists");
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = candidate();
            if (!exists.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("no unused incident ID after " + MAX_ATTEMPTS + " attempts");
    }

    private String candidate() {
        byte[] bytes = new byte[RANDOM_BYTES];
        random.nextBytes(bytes);
        return "inc-" + HEX.formatHex(bytes);
    }
}
