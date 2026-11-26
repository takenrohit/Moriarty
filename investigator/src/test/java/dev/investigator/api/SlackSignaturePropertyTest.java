package dev.investigator.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.investigator.testsupport.PropertySeeds;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.SplittableRandom;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

// Feature: reliability, Property 6: Slack signature verification
/**
 * A correctly signed request within the 5-minute window verifies; a single changed body byte, timestamp
 * or signature hex digit, or a timestamp outside the window, fails.
 *
 * <p><b>Validates: Requirements 7.2, 7.3</b>
 */
class SlackSignaturePropertyTest {

    private static final String HEX = "0123456789abcdef";
    private static final long WINDOW = SlackSignatureVerifier.MAX_SKEW.toSeconds();

    @ParameterizedTest(name = "seed={0}")
    @MethodSource("dev.investigator.testsupport.PropertySeeds#seeds")
    void onlyAnUnchangedSignedRequestWithinTheWindowVerifies(long seed) {
        PropertySeeds.check(seed, r -> {
            String secret = hex(r, 8 + r.nextInt(57));
            long now = 1_600_000_000L + r.nextLong(400_000_000L);
            SlackSignatureVerifier verifier =
                    new SlackSignatureVerifier(secret, Clock.fixed(Instant.ofEpochSecond(now), ZoneOffset.UTC));
            String timestamp = Long.toString(now + r.nextLong(-WINDOW, WINDOW + 1));
            byte[] body = new byte[1 + r.nextInt(2048)];
            r.nextBytes(body);
            String signature = SlackSignatureVerifier.sign(secret, timestamp, body);

            assertThat(verifier.verify(timestamp, body, signature)).as("signed request").isTrue();

            byte[] changedBody = body.clone();
            int index = r.nextInt(body.length);
            changedBody[index] = (byte) (changedBody[index] ^ (1 + r.nextInt(255)));
            assertThat(verifier.verify(timestamp, changedBody, signature)).as("body byte %d changed", index).isFalse();

            String otherTimestamp;
            do {
                otherTimestamp = Long.toString(now + r.nextLong(-WINDOW, WINDOW + 1));
            } while (otherTimestamp.equals(timestamp));
            assertThat(verifier.verify(otherTimestamp, body, signature)).as("timestamp changed").isFalse();

            int position = "v0=".length() + r.nextInt(signature.length() - "v0=".length());
            char digit;
            do {
                digit = HEX.charAt(r.nextInt(HEX.length()));
            } while (digit == signature.charAt(position));
            String changedSignature = signature.substring(0, position) + digit + signature.substring(position + 1);
            assertThat(verifier.verify(timestamp, body, changedSignature)).as("signature hex changed").isFalse();

            long outside = (WINDOW + 1 + r.nextLong(1_000_000L)) * (r.nextBoolean() ? 1 : -1);
            String stale = Long.toString(now + outside);
            assertThat(verifier.verify(stale, body, SlackSignatureVerifier.sign(secret, stale, body)))
                    .as("timestamp %ds from now", outside).isFalse();
        });
    }

    private static String hex(SplittableRandom r, int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(HEX.charAt(r.nextInt(HEX.length())));
        }
        return sb.toString();
    }
}
