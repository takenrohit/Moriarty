package dev.investigator.store.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Converts values for PostgreSQL columns (see {@code V1__baseline.sql}).
 *
 * <p>PostgreSQL {@code text} cannot hold NUL and {@code jsonb} rejects the {@code \u0000} escape, so
 * {@link #text} and {@link #jsonb} replace NUL with U+FFFD. Those columns are query copies; the exact
 * value is kept in the {@code json} {@code record} column written by {@link #json}. Instants are passed as
 * ISO-8601 strings and cast with {@code CAST(:x AS timestamptz)}.
 */
public final class PgValues {

    /** A {@code \u0000} escape not preceded by an escaping backslash. */
    private static final Pattern NUL_ESCAPE = Pattern.compile("(?<!\\\\)((?:\\\\\\\\)*)\\\\u0000");

    private PgValues() {
    }

    /** {@code value} with NUL replaced by U+FFFD; null stays null. */
    public static String text(String value) {
        return value == null ? null : value.replace('\u0000', '\uFFFD');
    }

    /** ISO-8601 string for a {@code timestamptz} cast; null stays null. */
    public static String timestamp(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    /** Exact JSON for a {@code json} column. */
    public static String json(ObjectMapper codec, Object value) {
        try {
            return codec.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("cannot serialize " + value.getClass().getSimpleName(), e);
        }
    }

    /** JSON for a {@code jsonb} column: {@link #json} with NUL escapes replaced by U+FFFD. */
    public static String jsonb(ObjectMapper codec, Object value) {
        return jsonbText(json(codec, value));
    }

    /** Already serialized JSON prepared for a {@code jsonb} column; null stays null. */
    public static String jsonbText(String json) {
        return json == null ? null : NUL_ESCAPE.matcher(json).replaceAll("$1\\\\ufffd");
    }
}
