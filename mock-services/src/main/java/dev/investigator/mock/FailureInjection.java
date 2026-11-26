package dev.investigator.mock;

import org.springframework.stereotype.Component;

/** In-memory fault switch. Only the provider role applies the delay. */
@Component
public class FailureInjection {

    private volatile long slowMs;

    public long slowMs() {
        return slowMs;
    }

    public void enableSlow(long ms) {
        slowMs = ms;
    }

    public void reset() {
        slowMs = 0;
    }
}
