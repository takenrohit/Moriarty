package dev.investigator.evidence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Active {@link Redactor} (NFR-5, Req 5.1 to 5.3, CP-72, CP-76): replaces secrets and PII in the payload's
 * string values with {@code [REDACTED:<category>]} placeholders.
 *
 * <p>Patterns run in a fixed order: {@code jwt}, {@code bearer_token}, {@code aws_key}, {@code credential}
 * ({@code password=}/{@code secret=}/{@code token=}/{@code api_key=} pairs, also JSON-style), {@code email},
 * then {@code card} (13 to 19 digits with optional single spaces or dashes, kept only when the Luhn check
 * passes). Map keys and non-string values are left alone; the input is never modified. Placeholders match
 * no pattern, so redaction is idempotent.
 *
 * <p>The redactor never throws. If processing a string fails unexpectedly, the whole string is replaced with
 * {@code [REDACTED:unprocessable]} (fail closed) rather than stored unredacted.
 */
@Component
public class RegexRedactor implements Redactor {

    static final String UNPROCESSABLE = "unprocessable";

    private static final String NOT_PLACEHOLDER = "(?!\\[REDACTED:)";

    /** Ordered rules; each replaces capture group {@code s} with the category placeholder. */
    private static final List<Rule> RULES = List.of(
            new Rule("jwt", Pattern.compile(
                    "(?<![A-Za-z0-9_-])(?<s>eyJ[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{4,})")),
            new Rule("bearer_token", Pattern.compile(
                    "(?i)(?<![A-Za-z0-9_])bearer\\s+" + NOT_PLACEHOLDER + "(?<s>[A-Za-z0-9\\-._~+/]{8,}=*)")),
            new Rule("aws_key", Pattern.compile("(?<![A-Za-z0-9])(?<s>(?:AKIA|ASIA)[A-Z0-9]{16})(?![A-Za-z0-9])")),
            new Rule("credential", Pattern.compile(
                    "(?i)(?<![A-Za-z0-9_-])[A-Za-z0-9_-]*(?:password|passwd|secret|token|api[_-]?key)"
                            + "\\s*[\"']?\\s*[:=]\\s*[\"']?" + NOT_PLACEHOLDER + "(?<s>[^\\s\"'&,;}]+)")),
            new Rule("email", Pattern.compile(
                    "(?<s>[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,})")));

    private static final String CARD = "card";
    private static final int CARD_MIN_DIGITS = 13;
    private static final int CARD_MAX_DIGITS = 19;

    @Override
    public RedactionResult redact(Map<String, Object> payload) {
        Set<String> categories = new LinkedHashSet<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> redacted = (Map<String, Object>) walk(payload, categories);
        return categories.isEmpty()
                ? new RedactionResult(payload, Set.of(), RedactionResult.NOT_APPLIED)
                : new RedactionResult(redacted, categories, RedactionResult.APPLIED);
    }

    private Object walk(Object value, Set<String> categories) {
        if (value instanceof String text) {
            return redactString(text, categories);
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, child) -> copy.put(key, walk(child, categories)));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(child -> copy.add(walk(child, categories)));
            return copy;
        }
        return value;
    }

    /** Redacts one string; on any unexpected failure the whole string is replaced (fail closed). */
    String redactString(String text, Set<String> categories) {
        try {
            String out = text;
            for (Rule rule : RULES) {
                out = rule.apply(out, categories);
            }
            return redactCards(out, categories);
        } catch (RuntimeException | StackOverflowError failure) {
            categories.add(UNPROCESSABLE);
            return placeholder(UNPROCESSABLE);
        }
    }

    private static String placeholder(String category) {
        return "[REDACTED:" + category + "]";
    }

    /**
     * Replaces card numbers. A candidate starts at a digit not preceded by a letter, digit, {@code _} or
     * {@code -} (so IDs such as {@code ev-inc-<hex>-<n>} are not candidates) and the longest Luhn-valid
     * prefix of 13 to 19 digits that ends at a word boundary is redacted.
     */
    private static String redactCards(String text, Set<String> categories) {
        StringBuilder out = null;
        int copied = 0;
        int i = 0;
        while (i < text.length()) {
            if (isDigit(text.charAt(i)) && (i == 0 || !isCardBoundaryBlocker(text.charAt(i - 1)))) {
                int end = cardEnd(text, i);
                if (end > 0) {
                    if (out == null) {
                        out = new StringBuilder(text.length());
                    }
                    out.append(text, copied, i).append(placeholder(CARD));
                    categories.add(CARD);
                    copied = end;
                    i = end;
                    continue;
                }
            }
            i++;
        }
        return out == null ? text : out.append(text, copied, text.length()).toString();
    }

    /** Exclusive end of the longest Luhn-valid card number starting at {@code start}, or -1. */
    private static int cardEnd(String text, int start) {
        int[] digits = new int[CARD_MAX_DIGITS];
        int[] ends = new int[CARD_MAX_DIGITS];
        int count = 0;
        int j = start;
        while (j < text.length() && count < CARD_MAX_DIGITS) {
            char c = text.charAt(j);
            if (isDigit(c)) {
                digits[count] = c - '0';
                ends[count++] = j + 1;
                j++;
            } else if ((c == ' ' || c == '-') && j + 1 < text.length() && isDigit(text.charAt(j + 1))) {
                j++;
            } else {
                break;
            }
        }
        for (int length = count; length >= CARD_MIN_DIGITS; length--) {
            int end = ends[length - 1];
            boolean boundary = end == text.length() || !isWordChar(text.charAt(end));
            if (boundary && luhn(digits, length)) {
                return end;
            }
        }
        return -1;
    }

    static boolean luhn(int[] digits, int length) {
        int sum = 0;
        for (int k = 0; k < length; k++) {
            int d = digits[length - 1 - k];
            if (k % 2 == 1) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
        }
        return sum % 10 == 0;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isWordChar(char c) {
        return isDigit(c) || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isCardBoundaryBlocker(char c) {
        return isWordChar(c) || c == '-';
    }

    private record Rule(String category, Pattern pattern) {

        String apply(String text, Set<String> categories) {
            Matcher matcher = pattern.matcher(text);
            if (!matcher.find()) {
                return text;
            }
            StringBuilder out = new StringBuilder(text.length());
            int copied = 0;
            do {
                out.append(text, copied, matcher.start("s")).append(placeholder(category));
                copied = matcher.end("s");
            } while (matcher.find());
            categories.add(category);
            return out.append(text, copied, text.length()).toString();
        }
    }
}
