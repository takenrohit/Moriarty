package dev.investigator.dedup;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The Dedup_Lock for a Fingerprint stayed held past the wait timeout; the alert is rejected with 503. */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class DedupLockTimeoutException extends RuntimeException {

    public DedupLockTimeoutException(String message) {
        super(message);
    }
}
