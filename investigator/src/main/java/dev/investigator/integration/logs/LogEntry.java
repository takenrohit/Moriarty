package dev.investigator.integration.logs;

import java.time.Instant;

/** One log line. */
public record LogEntry(Instant at, String level, String message) {
}
