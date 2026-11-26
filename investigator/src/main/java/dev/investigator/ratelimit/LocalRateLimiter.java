package dev.investigator.ratelimit;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * In-process {@link RateLimiter} used without the {@code redis} profile and as the fallback of
 * {@link RedisRateLimiter} (Req 6.4). One token bucket per {@code <scope>:<key>}; it does not limit across
 * instances.
 *
 * <p>Service names come from alerts, so the bucket map is bounded: past {@value #MAX_BUCKETS} buckets, the
 * full ones (which behave exactly like a missing bucket) are dropped before a new one is added.
 */
@Component
@Profile("!redis")
public class LocalRateLimiter implements RateLimiter {

    static final int MAX_BUCKETS = 10_000;

    private final RateLimitProperties properties;
    private final LongSupplier nanoTime;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Autowired
    public LocalRateLimiter(RateLimitProperties properties) {
        this(properties, System::nanoTime);
    }

    /** With a test time source in nanoseconds. */
    LocalRateLimiter(RateLimitProperties properties, LongSupplier nanoTime) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    @Override
    public boolean tryToolCall(String integration) {
        return tryTake(TOOL_SCOPE, integration, properties.toolCallsPerMinute(integration),
                RateLimitProperties.TOOL_PERIOD);
    }

    @Override
    public boolean tryInvestigation(String service) {
        return tryTake(INVESTIGATION_SCOPE, service, properties.investigationsPerServicePerHour(),
                RateLimitProperties.INVESTIGATION_PERIOD);
    }

    private boolean tryTake(String scope, String key, int capacity, Duration period) {
        long now = nanoTime.getAsLong();
        String id = scope + ":" + Objects.requireNonNull(key, "key");
        if (buckets.size() >= MAX_BUCKETS && !buckets.containsKey(id)) {
            buckets.values().removeIf(bucket -> bucket.isFull(now));
        }
        return buckets.computeIfAbsent(id, k -> new Bucket(capacity, period, now)).tryTake(now);
    }

    /** Token bucket refilled continuously at {@code capacity} tokens per {@code period}. */
    private static final class Bucket {

        private final double capacity;
        private final double tokensPerNano;
        private double tokens;
        private long last;

        Bucket(int capacity, Duration period, long now) {
            this.capacity = capacity;
            this.tokensPerNano = capacity / (double) period.toNanos();
            this.tokens = capacity;
            this.last = now;
        }

        synchronized boolean tryTake(long now) {
            refill(now);
            if (tokens < 1) {
                return false;
            }
            tokens -= 1;
            return true;
        }

        synchronized boolean isFull(long now) {
            refill(now);
            return tokens >= capacity;
        }

        private void refill(long now) {
            long elapsed = now - last;
            if (elapsed > 0) {
                tokens = Math.min(capacity, tokens + elapsed * tokensPerNano);
                last = now;
            }
        }
    }
}
