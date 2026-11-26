package dev.investigator.dedup;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * In-process {@link FingerprintLock} used when Redis is not configured (Req 11.5) and as the fallback of
 * {@link RedisFingerprintLock}. A fixed array of striped locks keeps memory bounded; two Fingerprints that
 * share a stripe only wait for each other briefly. It does not lock across instances.
 */
@Component
@Profile("!redis")
public class LocalFingerprintLock implements FingerprintLock {

    private static final int STRIPES = 64;

    private final ReentrantLock[] locks = new ReentrantLock[STRIPES];

    public LocalFingerprintLock() {
        for (int i = 0; i < STRIPES; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    @Override
    public <T> T withLock(String fingerprint, Supplier<T> body) {
        Objects.requireNonNull(fingerprint, "fingerprint");
        ReentrantLock lock = locks[Math.floorMod(fingerprint.hashCode(), STRIPES)];
        lock.lock();
        try {
            return body.get();
        } finally {
            lock.unlock();
        }
    }
}
