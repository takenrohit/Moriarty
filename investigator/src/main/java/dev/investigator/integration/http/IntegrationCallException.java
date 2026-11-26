package dev.investigator.integration.http;

/**
 * A real integration call failed: transport error, timeout, HTTP error status, or a response the client
 * cannot use. The Observability_Tools turn it into an error Tool_Result (Req 3.4). Messages never
 * contain credentials.
 *
 * <p>A client may attach a fixed {@link #hint()} when the model can fix the failure by changing its query
 * (for example a PromQL query that returns several series); the tools pass that hint to the model instead
 * of the generic one. A hint is a constant text and never echoes the query or the backend response.
 */
public class IntegrationCallException extends RuntimeException {

    private final String integration;
    private final String hint;

    public IntegrationCallException(String integration, String message) {
        this(integration, message, null, null);
    }

    public IntegrationCallException(String integration, String message, Throwable cause) {
        this(integration, message, null, cause);
    }

    private IntegrationCallException(String integration, String message, String hint, Throwable cause) {
        super(integration + ": " + message, cause);
        this.integration = integration;
        this.hint = hint;
    }

    /** A failure the model can recover from by following {@code hint}, a constant text. */
    public static IntegrationCallException withHint(String integration, String message, String hint) {
        return new IntegrationCallException(integration, message, hint, null);
    }

    /** {@code metrics}, {@code logs}, {@code traces} or {@code changes}. */
    public String integration() {
        return integration;
    }

    /** What the model should change in its next call, or {@code null} when there is no specific advice. */
    public String hint() {
        return hint;
    }
}
