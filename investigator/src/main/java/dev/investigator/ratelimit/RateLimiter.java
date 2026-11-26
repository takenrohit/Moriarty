package dev.investigator.ratelimit;

/**
 * Rate_Limiter: token buckets for tool calls per integration and for new investigations per service
 * (Req 6.1 to 6.4). {@link LocalRateLimiter} holds them in process; {@link RedisRateLimiter} holds them
 * in Redis under the {@code redis} profile and falls back to the local buckets on a Redis error.
 * Bucket sizes come from {@link RateLimitProperties}.
 */
public interface RateLimiter {

    /** Scope of the per-integration tool-call buckets. */
    String TOOL_SCOPE = "tool";

    /** Scope of the per-service investigation buckets. */
    String INVESTIGATION_SCOPE = "investigation";

    /**
     * Takes one token from the bucket of {@code integration} ({@code metrics}, {@code logs},
     * {@code traces}, {@code changes} or {@code runbooks}).
     *
     * @return false if the bucket is empty; the call must then not be made
     */
    boolean tryToolCall(String integration);

    /**
     * Takes one token from the investigation bucket of {@code service}.
     *
     * @return false if the bucket is empty; no investigation may start for the service
     */
    boolean tryInvestigation(String service);
}
