package dev.investigator.runbook;

/**
 * Thrown by {@link RunbookParser} when a file has missing or malformed front-matter or lacks
 * {@code title} or {@code service} (Req 12.3). The message never contains the file content.
 */
public class RunbookFormatException extends RuntimeException {

    public RunbookFormatException(String message) {
        super(message);
    }

    public RunbookFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
