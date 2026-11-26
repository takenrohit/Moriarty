package dev.investigator.dedup;

import java.util.function.Supplier;

/**
 * Dedup_Lock: serializes the dedup check and investigation start for one Fingerprint (Req 11.3, CP-50).
 * {@link RedisFingerprintLock} locks across instances ({@code redis} profile); {@link LocalFingerprintLock}
 * locks within this JVM only (Req 11.5).
 */
public interface FingerprintLock {

    /**
     * Runs {@code body} while holding the lock for {@code fingerprint} and returns its result.
     *
     * @throws DedupLockTimeoutException if the lock could not be acquired in time
     */
    <T> T withLock(String fingerprint, Supplier<T> body);
}
