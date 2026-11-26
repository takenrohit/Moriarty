package dev.investigator.ratelimit;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Cross-instance {@link RateLimiter} on Redis ({@code redis} profile, Req 6.3).
 *
 * <p>Each bucket is a hash {@code investigator:rl:<scope>:<key>} with the fields {@code tokens} and
 * {@code ts} (Redis server time in milliseconds). One Lua script refills, takes a token and writes the
 * bucket back atomically; the key expires after one idle period, when the bucket would be full anyway.
 *
 * <p>If Redis cannot be reached the call falls back to a {@link LocalRateLimiter} and logs a warning
 * (Req 6.4), mirroring {@code RedisFingerprintLock}.
 */
@Component
@Profile("redis")
public class RedisRateLimiter implements RateLimiter {

    static final String KEY_PREFIX = "investigator:rl:";

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);
    private static final RedisScript<Long> TAKE = new DefaultRedisScript<>("""
            local capacity = tonumber(ARGV[1])
            local period = tonumber(ARGV[2])
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(state[1])
            local ts = tonumber(state[2])
            if tokens == nil or ts == nil then
              tokens = capacity
              ts = now
            end
            tokens = math.min(capacity, tokens + math.max(0, now - ts) * capacity / period)
            local allowed = 0
            if tokens >= 1 then
              tokens = tokens - 1
              allowed = 1
            end
            redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
            redis.call('PEXPIRE', KEYS[1], period)
            return allowed
            """, Long.class);

    private final StringRedisTemplate redis;
    private final RateLimitProperties properties;
    private final LocalRateLimiter fallback;

    public RedisRateLimiter(StringRedisTemplate redis, RateLimitProperties properties) {
        this.redis = redis;
        this.properties = properties;
        this.fallback = new LocalRateLimiter(properties);
    }

    @Override
    public boolean tryToolCall(String integration) {
        Objects.requireNonNull(integration, "integration");
        try {
            return take(TOOL_SCOPE, integration, properties.toolCallsPerMinute(integration),
                    RateLimitProperties.TOOL_PERIOD);
        } catch (DataAccessException e) {
            warnFallback(TOOL_SCOPE, e);
            return fallback.tryToolCall(integration);
        }
    }

    @Override
    public boolean tryInvestigation(String service) {
        Objects.requireNonNull(service, "service");
        try {
            return take(INVESTIGATION_SCOPE, service, properties.investigationsPerServicePerHour(),
                    RateLimitProperties.INVESTIGATION_PERIOD);
        } catch (DataAccessException e) {
            warnFallback(INVESTIGATION_SCOPE, e);
            return fallback.tryInvestigation(service);
        }
    }

    private boolean take(String scope, String key, int capacity, Duration period) {
        Long allowed = redis.execute(TAKE, List.of(KEY_PREFIX + scope + ":" + key),
                Integer.toString(capacity), Long.toString(period.toMillis()));
        return allowed != null && allowed == 1L;
    }

    private static void warnFallback(String scope, DataAccessException e) {
        log.warn("Redis unavailable; rate limiting with the local bucket only scope={} error={}",
                scope, e.getClass().getSimpleName());
    }
}
