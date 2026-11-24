package dev.investigator.alert;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Computes the Fingerprint of an alert: the lowercase hex SHA-256 of the alert name, the service
 * and the labels sorted by key (Req 5.5, 5.7).
 *
 * <p>The hashed canonical string is {@code alertName NUL service NUL k1=v1 NUL k2=v2 ...} in UTF-8,
 * where NUL is U+0000 and labels are sorted by key ({@link String#compareTo}). Each component is
 * escaped first so that the encoding is injective: a backslash becomes two backslashes, NUL becomes
 * backslash + {@code 0}, {@code =} becomes backslash + {@code =}, and an unpaired surrogate (which
 * UTF-8 cannot encode) becomes backslash + {@code u} + 4 uppercase hex digits. Without the escaping,
 * the label maps {@code {"a=b": "c"}} and {@code {"a": "b=c"}} would share a fingerprint. Components
 * without these characters are hashed unchanged, so ordinary alerts hash exactly the plain canonical
 * string from the design.
 */
public final class Fingerprints {

    private static final char SEPARATOR = '\u0000';
    private static final HexFormat HEX = HexFormat.of();

    private Fingerprints() {
    }

    /**
     * Returns the 64-character lowercase hex Fingerprint.
     *
     * @param labels label map; may be null (treated as empty); keys and values must not be null
     */
    public static String of(String alertName, String service, Map<String, String> labels) {
        return HEX.formatHex(sha256(canonical(alertName, service, labels).getBytes(StandardCharsets.UTF_8)));
    }

    /** The canonical string that {@link #of} hashes. Visible for tests. */
    static String canonical(String alertName, String service, Map<String, String> labels) {
        Objects.requireNonNull(alertName, "alertName");
        Objects.requireNonNull(service, "service");
        StringBuilder out = new StringBuilder();
        escape(alertName, out);
        out.append(SEPARATOR);
        escape(service, out);
        if (labels != null) {
            for (Map.Entry<String, String> label : new TreeMap<>(labels).entrySet()) {
                out.append(SEPARATOR);
                escape(Objects.requireNonNull(label.getKey(), "label key"), out);
                out.append('=');
                escape(Objects.requireNonNull(label.getValue(), "label value"), out);
            }
        }
        return out.toString();
    }

    private static void escape(String value, StringBuilder out) {
        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            switch (codePoint) {
                case '\\' -> out.append("\\\\");
                case SEPARATOR -> out.append("\\0");
                case '=' -> out.append("\\=");
                default -> {
                    if (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) {
                        out.append(String.format(Locale.ROOT, "\\u%04X", codePoint));
                    } else {
                        out.appendCodePoint(codePoint);
                    }
                }
            }
            i += Character.charCount(codePoint);
        }
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
