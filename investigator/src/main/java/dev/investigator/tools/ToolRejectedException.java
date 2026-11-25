package dev.investigator.tools;

import java.util.Objects;

/**
 * A tool invocation refused by the {@link ToolGuard} or by argument parsing (Req 10.9, 11). The
 * tools turn it into {@code {"error": <error>, "hint": <hint>}} through {@link ToolResults#error}.
 *
 * <p>Neither text echoes the rejected argument value, so model-supplied text is not reflected back.
 * The exception carries no stack trace; it is control flow, not a failure.
 */
public final class ToolRejectedException extends RuntimeException {

    private final String error;
    private final String hint;

    public ToolRejectedException(String error, String hint) {
        super(Objects.requireNonNull(error, "error"), null, false, false);
        this.error = error;
        this.hint = Objects.requireNonNull(hint, "hint");
    }

    /** What was wrong, for example {@code "service is not in the allow-list"}. */
    public String error() {
        return error;
    }

    /** What the model should do instead. */
    public String hint() {
        return hint;
    }

    /** The JSON Tool_Result for this rejection. */
    public String toResult() {
        return ToolResults.error(error, hint);
    }
}
