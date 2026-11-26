package dev.investigator.integration.http;

/**
 * A real integration call failed: transport error, timeout, HTTP error status, or a response the client
 * cannot use. The Observability_Tools turn it into an error Tool_Result (Req 3.4). Messages never
 * contain credentials.
 */
public class IntegrationCallException extends RuntimeException {

    private final String integration;

    public IntegrationCallException(String integration, String message) {
        super(integration + ": " + message);
        this.integration = integration;
    }

    public IntegrationCallException(String integration, String message, Throwable cause) {
        super(integration + ": " + message, cause);
        this.integration = integration;
    }

    /** {@code metrics}, {@code logs}, {@code traces} or {@code changes}. */
    public String integration() {
        return integration;
    }
}
