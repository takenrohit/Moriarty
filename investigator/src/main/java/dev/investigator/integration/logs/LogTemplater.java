package dev.investigator.integration.logs;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Clusters log messages into templates by replacing variable tokens with placeholders, so messages that
 * differ only in those tokens share one template (Req 4.4, 4.6).
 *
 * <p>Replacements, applied in this order:
 * <ol>
 *   <li>timestamps (ISO-8601 dates and date-times, with {@code T} or a space, optional fraction and offset,
 *       and bare {@code HH:mm:ss} times) become {@value #TIMESTAMP};</li>
 *   <li>UUIDs become {@value #UUID};</li>
 *   <li>double-quoted, back-quoted and single-quoted strings become {@value #QUOTED}; a single quote starts a
 *       string only when it does not follow a letter or digit, so apostrophes such as {@code can't} stay;</li>
 *   <li>hexadecimal IDs ({@code 0x}-prefixed, or 8 or more hex digits mixing letters and digits) become
 *       {@value #HEX};</li>
 *   <li>numbers (digits not preceded by a letter, digit or underscore, with an optional minus sign and
 *       fraction) become {@value #NUMBER}, so {@code 5000ms} becomes {@code <NUM>ms} while {@code user42} is
 *       kept.</li>
 * </ol>
 * Everything else, including whitespace, is kept, so messages with different words get different templates.
 */
public final class LogTemplater {

    public static final String TIMESTAMP = "<TS>";
    public static final String UUID = "<UUID>";
    public static final String QUOTED = "<STR>";
    public static final String HEX = "<HEX>";
    public static final String NUMBER = "<NUM>";

    private static final String NOT_AFTER_WORD = "(?<![A-Za-z0-9_])";
    private static final String NOT_BEFORE_WORD = "(?![A-Za-z0-9_])";

    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile(
            NOT_AFTER_WORD + "\\d{4}-\\d{2}-\\d{2}(?:[T ]\\d{2}:\\d{2}(?::\\d{2}(?:[.,]\\d+)?)?)?(?:Z|[+-]\\d{2}:?\\d{2})?"
                    + "|" + NOT_AFTER_WORD + "\\d{1,2}:\\d{2}:\\d{2}(?:[.,]\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?");
    private static final Pattern UUID_PATTERN = Pattern.compile(NOT_AFTER_WORD
            + "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}" + NOT_BEFORE_WORD);
    private static final Pattern QUOTED_PATTERN = Pattern.compile(
            "\"(?:[^\"\\\\]|\\\\.)*\"|`[^`]*`|(?<![A-Za-z0-9])'(?:[^'\\\\\\n]|\\\\.)*'");
    private static final Pattern HEX_PATTERN = Pattern.compile(NOT_AFTER_WORD
            + "(?:0[xX][0-9a-fA-F]+|(?=[0-9a-fA-F]*[a-fA-F])(?=[0-9a-fA-F]*[0-9])[0-9a-fA-F]{8,})" + NOT_BEFORE_WORD);
    private static final Pattern NUMBER_PATTERN = Pattern.compile(NOT_AFTER_WORD + "-?\\d+(?:\\.\\d+)?");

    private LogTemplater() {
    }

    /** The template of {@code message}: its variable tokens replaced by placeholders. */
    public static String template(String message) {
        Objects.requireNonNull(message, "message");
        String t = TIMESTAMP_PATTERN.matcher(message).replaceAll(TIMESTAMP);
        t = UUID_PATTERN.matcher(t).replaceAll(UUID);
        t = QUOTED_PATTERN.matcher(t).replaceAll(QUOTED);
        t = HEX_PATTERN.matcher(t).replaceAll(HEX);
        return NUMBER_PATTERN.matcher(t).replaceAll(NUMBER);
    }
}
