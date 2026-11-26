package dev.investigator.api;

import dev.investigator.config.SlackProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Verifies Slack request signatures (Req 7.2, 7.3): {@code X-Slack-Signature} must equal
 * {@code v0=} + lowercase hex of HMAC-SHA256(signing secret, {@code v0:<timestamp>:<raw body>}), compared
 * with {@link MessageDigest#isEqual}, and {@code X-Slack-Request-Timestamp} must be within
 * {@link #MAX_SKEW} of the injected {@link Clock}. Without a signing secret nothing verifies.
 */
@Component
public class SlackSignatureVerifier {

    /** Largest accepted difference between the request timestamp and now. */
    public static final Duration MAX_SKEW = Duration.ofMinutes(5);

    static final String TIMESTAMP_HEADER = "X-Slack-Request-Timestamp";
    static final String SIGNATURE_HEADER = "X-Slack-Signature";

    private static final String VERSION = "v0";
    private static final Pattern TIMESTAMP = Pattern.compile("\\d{1,12}");
    private static final HexFormat HEX = HexFormat.of();

    private final byte[] secret;
    private final Clock clock;

    @Autowired
    public SlackSignatureVerifier(SlackProperties properties, Clock clock) {
        this(properties.signingSecret(), clock);
    }

    public SlackSignatureVerifier(String signingSecret, Clock clock) {
        this.secret = StringUtils.hasText(signingSecret) ? signingSecret.strip().getBytes(StandardCharsets.UTF_8) : null;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Whether a signing secret is configured. */
    public boolean enabled() {
        return secret != null;
    }

    /** Whether {@code signature} is the valid {@code v0} signature of {@code body} at a current {@code timestamp}. */
    public boolean verify(String timestamp, byte[] body, String signature) {
        if (secret == null || timestamp == null || body == null || signature == null
                || !TIMESTAMP.matcher(timestamp).matches()) {
            return false;
        }
        long skew = Math.abs(clock.instant().getEpochSecond() - Long.parseLong(timestamp));
        if (skew > MAX_SKEW.toSeconds()) {
            return false;
        }
        byte[] expected = sign(secret, timestamp, body).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.UTF_8));
    }

    /** The {@code v0=<hex>} signature Slack sends for {@code body} at {@code timestamp}. */
    public static String sign(String signingSecret, String timestamp, byte[] body) {
        return sign(signingSecret.strip().getBytes(StandardCharsets.UTF_8), timestamp, body);
    }

    private static String sign(byte[] key, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update((VERSION + ":" + timestamp + ":").getBytes(StandardCharsets.UTF_8));
            return VERSION + "=" + HEX.formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}
