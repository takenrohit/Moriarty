package dev.investigator.dedup;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Cross-instance Dedup_Lock on Redis ({@code redis} profile, Req 11.3, CP-50).
 *
 * <p>Acquire is {@code SET investigator:dedup:<fingerprint> <token> NX PX <ttl>}, retried with
 * exponential backoff (25 ms doubling to 200 ms) until the wait timeout, after which the alert is rejected
 * with {@link DedupLockTimeoutException}. Release deletes the key only if it still holds this caller's
 * token (Lua compare-and-delete), so a lock that expired and was taken by another caller is never
 * removed. The TTL bounds how long a crashed holder blocks others.
 *
 * <p>If Redis cannot be reached while acquiring, the call falls back to a {@link LocalFingerprintLock}
 * and logs a warning: the alert is still deduplicated within this instance (Req 11.5) instead of failing.
 */
@Component
@Profile("redis")
public class RedisFingerprintLock implements FingerprintLock {

    static final String KEY_PREFIX = "investigator:dedup:";

    private static final Logger log = LoggerFactory.getLogger(RedisFingerprintLock.class);
    private static final RedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);
    private static final long MIN_BACKOFF_MS = 25;
    private static final long MAX_BACKOFF_MS = 200;

    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final Duration waitTimeout;
    private final LocalFingerprintLock fallback = new LocalFingerprintLock();

    @Autowired
    public RedisFingerprintLock(StringRedisTemplate redis,
            @Value("${investigator.dedup.lock-ttl:30s}") Duration ttl,
            @Value("${investigator.dedup.lock-wait:10s}") Duration waitTimeout) {
        this.redis = redis;
        this.ttl = ttl;
        this.waitTimeout = waitTimeout;
    }

    @Override
    public <T> T withLock(String fingerprint, Supplier<T> body) {
        Objects.requireNonNull(fingerprint, "fingerprint");
        String key = KEY_PREFIX + fingerprint;
        String token = UUID.randomUUID().toString();
        try {
            acquire(key, token);
        } catch (DataAccessException e) {
            log.warn("Redis unavailable; deduplicating with the local lock only fingerprint={} error={}",
                    fingerprint, e.getClass().getSimpleName());
            return fallback.withLock(fingerprint, body);
        }
        try {
            return body.get();
        } finally {
            release(key, token, fingerprint);
        }
    }

    private void acquire(String key, String token) {
        long deadline = System.nanoTime() + waitTimeout.toNanos();
        long backoff = MIN_BACKOFF_MS;
        while (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, token, ttl))) {
            if (System.nanoTime() >= deadline) {
                throw new DedupLockTimeoutException("timed out waiting for the dedup lock");
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DedupLockTimeoutException("interrupted while waiting for the dedup lock");
            }
            backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
        }
    }

    private void release(String key, String token, String fingerprint) {
        try {
            redis.execute(RELEASE, List.of(key), token);
        } catch (DataAccessException e) {
            log.warn("could not release the dedup lock; it expires after its TTL fingerprint={} error={}",
                    fingerprint, e.getClass().getSimpleName());
        }
    }
}
