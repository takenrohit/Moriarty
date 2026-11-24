package dev.investigator.evidence;

import java.math.BigDecimal;
import java.math.MathContext;

/** Text helpers for summary headlines and string truncation. */
public final class Headlines {

    /** Maximum headline length in code points. */
    public static final int MAX_LENGTH = 200;

    /** Appended to truncated strings; counts as one code point. */
    public static final String ELLIPSIS = "…";

    private Headlines() {
    }

    /**
     * Makes {@code text} a headline: every run of whitespace (including line breaks) becomes one space,
     * the result is trimmed and then truncated to {@link #MAX_LENGTH} code points. Null becomes "".
     */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return truncate(text.replaceAll("\\s+", " ").trim(), MAX_LENGTH);
    }

    /**
     * Truncates {@code text} to at most {@code maxCodePoints} code points, ending in {@link #ELLIPSIS}
     * when anything was cut. Surrogate pairs are never split. Null stays null.
     *
     * @param maxCodePoints at least 1
     */
    public static String truncate(String text, int maxCodePoints) {
        if (maxCodePoints < 1) {
            throw new IllegalArgumentException("maxCodePoints must be at least 1");
        }
        if (text == null || text.codePointCount(0, text.length()) <= maxCodePoints) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, maxCodePoints - 1)) + ELLIPSIS;
    }

    /** Formats a number with at most 4 significant digits and no exponent, for example {@code 0.1834}. */
    static String number(Double value) {
        if (value == null) {
            return "n/a";
        }
        if (!Double.isFinite(value)) {
            return value.toString();
        }
        BigDecimal rounded = new BigDecimal(value).round(new MathContext(4)).stripTrailingZeros();
        return rounded.signum() == 0 ? "0" : rounded.toPlainString();
    }
}
