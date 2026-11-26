package dev.investigator.testsupport;

import dev.investigator.integration.logs.LogTemplater;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Seeded generators of log messages for the log clustering property (Property 3): a {@link Template} of
 * literal words and variable slots, rendered into messages with fresh variable tokens on every call.
 *
 * <p>Words are lower-case ASCII letters, so no word is a variable token. Parts are joined by separators
 * that are never letters or digits ({@code " "}, {@code ": "}, {@code "="}, {@code ", "}, {@code " - "},
 * {@code "/"}), so a token never merges with its neighbour.
 */
public final class LogMessageGenerators {

    /** Kinds of template part. */
    public enum Kind { WORD, NUMBER, UUID, HEX, QUOTED, TIMESTAMP }

    /** One part: a literal word (its text) or a variable slot (text unused). */
    public record Part(Kind kind, String word) {
    }

    /**
     * A message template.
     *
     * @param parts words and slots in order, at least one word
     * @param separators {@code parts.size() - 1} separators placed between consecutive parts
     */
    public record Template(List<Part> parts, List<String> separators) {

        public Template {
            parts = List.copyOf(parts);
            separators = List.copyOf(separators);
        }
    }

    private static final String[] SEPARATORS = {" ", ": ", "=", ", ", " - ", "/"};
    private static final Kind[] SLOTS = {Kind.NUMBER, Kind.UUID, Kind.HEX, Kind.QUOTED, Kind.TIMESTAMP};
    private static final String HEX_DIGITS = "0123456789abcdef";
    private static final String HEX_LETTERS = "abcdef";
    private static final String QUOTED_CHARS = "abcdefghijklmnopqrstuvwxyz ABCXYZ0123456789-_.:";
    private static final DateTimeFormatter SPACE_FORM = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final long ANCHOR_SECONDS = DomainGenerators.ANCHOR.getEpochSecond();

    private LogMessageGenerators() {
    }

    /** A template of 1 to 8 parts with at least one word; about 1 part in 2 is a slot. */
    public static Template template(SplittableRandom r) {
        int size = r.nextInt(1, 9);
        int forcedWord = r.nextInt(size);
        List<Part> parts = new ArrayList<>(size);
        List<String> separators = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            parts.add(i == forcedWord || r.nextBoolean()
                    ? new Part(Kind.WORD, word(r))
                    : new Part(SLOTS[r.nextInt(SLOTS.length)], null));
            if (i > 0) {
                separators.add(SEPARATORS[r.nextInt(SEPARATORS.length)]);
            }
        }
        return new Template(parts, separators);
    }

    /** {@code template} with one word replaced by a different word. */
    public static Template withChangedWord(SplittableRandom r, Template template) {
        List<Integer> words = new ArrayList<>();
        for (int i = 0; i < template.parts().size(); i++) {
            if (template.parts().get(i).kind() == Kind.WORD) {
                words.add(i);
            }
        }
        int index = words.get(r.nextInt(words.size()));
        String old = template.parts().get(index).word();
        String replacement = word(r);
        while (replacement.equals(old)) {
            replacement = word(r);
        }
        List<Part> parts = new ArrayList<>(template.parts());
        parts.set(index, new Part(Kind.WORD, replacement));
        return new Template(parts, template.separators());
    }

    /** A message from {@code template}: its words and separators, each slot filled with a fresh token. */
    public static String render(SplittableRandom r, Template template) {
        StringBuilder message = new StringBuilder();
        for (int i = 0; i < template.parts().size(); i++) {
            if (i > 0) {
                message.append(template.separators().get(i - 1));
            }
            Part part = template.parts().get(i);
            message.append(part.kind() == Kind.WORD ? part.word() : token(r, part.kind()));
        }
        return message.toString();
    }

    /** The template text {@link LogTemplater} must produce: slots replaced by their placeholders. */
    public static String expected(Template template) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < template.parts().size(); i++) {
            if (i > 0) {
                text.append(template.separators().get(i - 1));
            }
            Part part = template.parts().get(i);
            text.append(switch (part.kind()) {
                case WORD -> part.word();
                case NUMBER -> LogTemplater.NUMBER;
                case UUID -> LogTemplater.UUID;
                case HEX -> LogTemplater.HEX;
                case QUOTED -> LogTemplater.QUOTED;
                case TIMESTAMP -> LogTemplater.TIMESTAMP;
            });
        }
        return text.toString();
    }

    private static String word(SplittableRandom r) {
        int length = r.nextInt(1, 9);
        StringBuilder word = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            word.append((char) ('a' + r.nextInt(26)));
        }
        return word.toString();
    }

    private static String token(SplittableRandom r, Kind kind) {
        return switch (kind) {
            case NUMBER -> number(r);
            case UUID -> java.util.UUID.nameUUIDFromBytes(Long.toString(r.nextLong()).getBytes()).toString();
            case HEX -> hex(r);
            case QUOTED -> quoted(r);
            case TIMESTAMP -> timestamp(r);
            case WORD -> throw new IllegalArgumentException("not a slot");
        };
    }

    /** An integer or decimal, sometimes negative, from 1 to 12 digits. */
    private static String number(SplittableRandom r) {
        String sign = r.nextInt(5) == 0 ? "-" : "";
        String whole = Long.toString(r.nextLong(0, (long) Math.pow(10, r.nextInt(1, 13))));
        return sign + whole + (r.nextBoolean() ? "" : "." + r.nextInt(0, 1000));
    }

    /** {@code 0x} with 1 to 16 hex digits, or 8 to 32 hex digits with at least one letter and one digit. */
    private static String hex(SplittableRandom r) {
        if (r.nextInt(3) == 0) {
            return (r.nextBoolean() ? "0x" : "0X") + hexDigits(r, r.nextInt(1, 17));
        }
        char[] digits = hexDigits(r, r.nextInt(8, 33)).toCharArray();
        int letterAt = r.nextInt(digits.length);
        int digitAt = (letterAt + 1 + r.nextInt(digits.length - 1)) % digits.length;
        digits[letterAt] = HEX_LETTERS.charAt(r.nextInt(HEX_LETTERS.length()));
        digits[digitAt] = (char) ('0' + r.nextInt(10));
        String text = new String(digits);
        return r.nextBoolean() ? text : text.toUpperCase();
    }

    private static String hexDigits(SplittableRandom r, int length) {
        StringBuilder text = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            text.append(HEX_DIGITS.charAt(r.nextInt(HEX_DIGITS.length())));
        }
        return text.toString();
    }

    /** A double-, single- or back-quoted string, possibly empty; double quotes may contain {@code \"}. */
    private static String quoted(SplittableRandom r) {
        StringBuilder content = new StringBuilder();
        int length = r.nextInt(0, 16);
        for (int i = 0; i < length; i++) {
            content.append(QUOTED_CHARS.charAt(r.nextInt(QUOTED_CHARS.length())));
        }
        return switch (r.nextInt(3)) {
            case 0 -> "\"" + content + (r.nextBoolean() ? "\\\"" : "") + "\"";
            case 1 -> "'" + content + "'";
            default -> "`" + content + "`";
        };
    }

    /** An ISO-8601 instant, a space-separated local date-time with millis, or a date-time with an offset. */
    private static String timestamp(SplittableRandom r) {
        Instant at = Instant.ofEpochSecond(ANCHOR_SECONDS - r.nextLong(0, 10L * 365 * 86_400),
                r.nextBoolean() ? 0 : r.nextInt(1_000_000_000));
        return switch (r.nextInt(3)) {
            case 0 -> at.toString();
            case 1 -> SPACE_FORM.format(LocalDateTime.ofInstant(at, ZoneOffset.UTC));
            default -> at.atOffset(ZoneOffset.ofHoursMinutes(5, 30)).toString();
        };
    }
}
