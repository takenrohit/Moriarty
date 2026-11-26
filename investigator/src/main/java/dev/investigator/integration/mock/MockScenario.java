package dev.investigator.integration.mock;

import dev.investigator.integration.changes.ChangeRecord;
import dev.investigator.integration.logs.LogEntry;
import dev.investigator.integration.logs.LogSearchResult;
import dev.investigator.integration.metrics.MetricPoint;
import dev.investigator.integration.metrics.MetricSeries;
import dev.investigator.integration.traces.SpanRecord;
import dev.investigator.integration.traces.TraceRecord;
import dev.investigator.integration.traces.TraceSearchResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The demo failure of {@code demo-scenario.md}, generated relative to a Scenario_Anchor {@code a}
 * (design "Integration interfaces and mock scenario").
 *
 * <p>For the demo alert ({@code a = 2026-11-23T14:40:00Z}, 20:10 IST): checkout-api v2.14.3 is
 * deployed at {@code a−14m} (19:56 IST) with a copy-only change; payment-gateway p99 latency rises from
 * 180 ms to 5000 ms at {@code a−3m} (20:07 IST); from {@code a−2m} (20:08 IST) the checkout-api 5xx
 * ratio is 0.12 instead of 0.002, checkout-api logs {@code PaymentGatewayTimeoutException} every 2 s
 * (214 entries), and traces fail in the {@code payment-gateway → payment-provider} call. Every other
 * service, and every other metric kind, returns a healthy baseline. This is {@link ScenarioKind#PAYMENT_GATEWAY},
 * the default ({@link #forAnchor(Instant)}).
 *
 * <p>The other kinds of the Scenario_Library (Req 10.3, CP-75), all failing in checkout-api:
 * {@link ScenarioKind#BAD_DEPLOY} (v2.14.3 at {@code a−14m} changes {@code PaymentClient}/{@code RetryPolicy};
 * from {@code a−12m} 5xx 0.12, {@code NullPointerException at PaymentClient.authorize} logs and failing
 * spans inside checkout-api; payment-gateway healthy), {@link ScenarioKind#DB_POOL_EXHAUSTION} (from
 * {@code a−5m} 5xx 0.08, p99 30 s, HikariPool timeout logs, failing {@code checkout-api → postgres}
 * spans; last deploy {@code a−26h}) and {@link ScenarioKind#NOISY_NEIGHBOUR} (from {@code a−6m} p99
 * 2400 ms and CPU/throttling ratio 0.97, GC-overhead and slow-request WARN logs, slow but error-free
 * traces with a fast payment-gateway call; last deploy {@code a−26h}).
 *
 * <p>The class is pure: results depend only on the kind, the anchor and the arguments. Every timestamp is
 * {@code a + offset} or {@code start + k·step}, so shifting the anchor and the query range by {@code d}
 * shifts the output by {@code d} (Req 8.10). Every result is filtered to the queried range, bounds
 * inclusive (Req 8.11).
 */
public final class MockScenario {

    public static final String CHECKOUT_API = "checkout-api";
    public static final String PAYMENT_GATEWAY = "payment-gateway";
    public static final String PAYMENT_PROVIDER = "payment-provider";

    /** Offset of the checkout-api error spike, first timeout log and first failed trace. */
    public static final Duration ERROR_ONSET = Duration.ofMinutes(-2);
    /** Offset of the payment-gateway latency jump. */
    public static final Duration LATENCY_ONSET = Duration.ofMinutes(-3);
    /** Offset of the checkout-api v2.14.3 deployment. */
    public static final Duration DEPLOY_OFFSET = Duration.ofMinutes(-14);
    /** Offset of the earlier checkout-api v2.14.2 deployment, outside the 24 h lookback. */
    public static final Duration PREVIOUS_DEPLOY_OFFSET = Duration.ofHours(-26);

    public static final double ERROR_RATIO_HEALTHY = 0.002;
    public static final double ERROR_RATIO_INCIDENT = 0.12;
    public static final double GATEWAY_P99_HEALTHY_MS = 180;
    public static final double GATEWAY_P99_INCIDENT_MS = 5000;
    public static final double BASELINE_P99_MS = 120;
    public static final double BASELINE_REQUEST_RATE = 50;

    public static final String TIMEOUT_MESSAGE =
            "PaymentGatewayTimeoutException: call to payment-gateway timed out after 5000 ms";
    public static final int TIMEOUT_LOG_COUNT = 214;
    public static final Duration TIMEOUT_LOG_INTERVAL = Duration.ofSeconds(2);
    public static final String CHECKOUT_INFO_MESSAGE = "checkout completed";
    public static final String BASELINE_INFO_MESSAGE = "request completed";
    /** Offset of the first INFO log entry of every service. */
    public static final Duration INFO_LOG_START = Duration.ofMinutes(-70);
    public static final Duration INFO_LOG_INTERVAL = Duration.ofSeconds(60);

    public static final int FAILED_TRACE_COUNT = 60;
    public static final Duration FAILED_TRACE_INTERVAL = Duration.ofSeconds(6);
    public static final Duration FAILED_TRACE_DURATION = Duration.ofMillis(5020);

    // BAD_DEPLOY: checkout-api v2.14.3 (a−14m) breaks the payment client; errors from a−12m.
    public static final Duration BAD_DEPLOY_ERROR_ONSET = Duration.ofMinutes(-12);
    public static final List<String> BAD_DEPLOY_FILES = List.of(
            "src/main/java/com/example/checkout/payment/PaymentClient.java",
            "src/main/java/com/example/checkout/payment/RetryPolicy.java");
    public static final String NPE_MESSAGE =
            "NullPointerException at PaymentClient.authorize(PaymentClient.java:87)";
    public static final int NPE_LOG_COUNT = 330;
    public static final Duration NPE_LOG_INTERVAL = Duration.ofSeconds(4);

    // DB_POOL_EXHAUSTION: checkout-api waits for database connections from a−5m; no recent deploy.
    public static final Duration DB_POOL_ONSET = Duration.ofMinutes(-5);
    public static final double DB_POOL_ERROR_RATIO = 0.08;
    public static final double DB_POOL_P99_MS = 30000;
    public static final String HIKARI_MESSAGE =
            "HikariPool-1 - Connection is not available, request timed out after 30000ms";
    public static final int HIKARI_LOG_COUNT = 150;
    public static final Duration HIKARI_LOG_INTERVAL = Duration.ofSeconds(6);
    public static final String POSTGRES = "postgres";

    // NOISY_NEIGHBOUR: checkout-api is CPU-throttled from a−6m; dependencies healthy; no recent deploy.
    public static final Duration CPU_ONSET = Duration.ofMinutes(-6);
    public static final double CPU_HEALTHY = 0.15;
    public static final double CPU_INCIDENT = 0.97;
    public static final double NOISY_P99_MS = 2400;
    public static final String GC_MESSAGE = "GC overhead high: 41% of CPU time spent in garbage collection";
    public static final String SLOW_REQUEST_MESSAGE = "slow request POST /checkout took 2400 ms";

    private static final Pattern LINE_FILTER = Pattern.compile("\\|=\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|`([^`]*)`)");

    private static final List<SpanRecord> FAILED_TRACE_SPANS = List.of(
            new SpanRecord(CHECKOUT_API, "POST /checkout", null, FAILED_TRACE_DURATION, true),
            new SpanRecord(PAYMENT_GATEWAY, "POST /charge", null, Duration.ofMillis(5010), true),
            new SpanRecord(PAYMENT_GATEWAY, "POST /authorize", PAYMENT_PROVIDER, Duration.ofMillis(5000), true));

    private static final TraceShape PAYMENT_GATEWAY_TRACES = new TraceShape(List.of(CHECKOUT_API, PAYMENT_GATEWAY),
            ERROR_ONSET, FAILED_TRACE_DURATION, true, FAILED_TRACE_SPANS);
    private static final TraceShape BAD_DEPLOY_TRACES = new TraceShape(List.of(CHECKOUT_API),
            BAD_DEPLOY_ERROR_ONSET, Duration.ofMillis(45), true, List.of(
                    new SpanRecord(CHECKOUT_API, "POST /checkout", null, Duration.ofMillis(45), true),
                    new SpanRecord(CHECKOUT_API, "PaymentClient.authorize", null, Duration.ofMillis(3), true)));
    private static final TraceShape DB_POOL_TRACES = new TraceShape(List.of(CHECKOUT_API),
            DB_POOL_ONSET, Duration.ofMillis(30020), true, List.of(
                    new SpanRecord(CHECKOUT_API, "POST /checkout", null, Duration.ofMillis(30020), true),
                    new SpanRecord(CHECKOUT_API, "SELECT orders", POSTGRES, Duration.ofMillis(30000), true)));
    private static final TraceShape NOISY_TRACES = new TraceShape(List.of(CHECKOUT_API),
            CPU_ONSET, Duration.ofMillis(2400), false, List.of(
                    new SpanRecord(CHECKOUT_API, "POST /checkout", null, Duration.ofMillis(2400), false),
                    new SpanRecord(PAYMENT_GATEWAY, "POST /charge", null, Duration.ofMillis(150), false)));

    private final ScenarioKind kind;
    private final Instant anchor;

    private MockScenario(ScenarioKind kind, Instant anchor) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.anchor = Objects.requireNonNull(anchor, "anchor");
    }

    /** The default payment-gateway scenario anchored at {@code anchor} (normally the Incident's {@code detectedAt}). */
    public static MockScenario forAnchor(Instant anchor) {
        return new MockScenario(ScenarioKind.DEFAULT, anchor);
    }

    /** The {@code kind} scenario anchored at {@code anchor}; pure in {@code (kind, anchor)} (Req 10.5). */
    public static MockScenario forAnchor(ScenarioKind kind, Instant anchor) {
        return new MockScenario(kind, anchor);
    }

    public ScenarioKind kind() {
        return kind;
    }

    public Instant anchor() {
        return anchor;
    }

    /** The kind of metric a PromQL query asks for, decided by substrings of the query. */
    public enum MetricKind {
        /** Query contains {@code 5..}, {@code 5xx} or {@code status=~"5}. */
        ERROR_RATIO,
        /** Query contains {@code histogram_quantile}, {@code latency} or {@code duration}; values in ms. */
        LATENCY_P99,
        /** Query contains {@code cpu} or {@code throttl}; values are a 0..1 usage/throttling ratio. */
        CPU,
        /** Any other query. */
        REQUEST_RATE
    }

    /** Classifies a PromQL query; error-ratio patterns take precedence over latency, then CPU patterns. */
    public static MetricKind classify(String promql) {
        String q = Objects.requireNonNull(promql, "promql").toLowerCase(Locale.ROOT);
        if (q.contains("5..") || q.contains("5xx") || q.contains("status=~\"5")) {
            return MetricKind.ERROR_RATIO;
        }
        if (q.contains("histogram_quantile") || q.contains("latency") || q.contains("duration")) {
            return MetricKind.LATENCY_P99;
        }
        if (q.contains("cpu") || q.contains("throttl")) {
            return MetricKind.CPU;
        }
        return MetricKind.REQUEST_RATE;
    }

    /**
     * Range query: points at {@code start + k·step} for {@code k ≥ 0} while {@code ≤ end}.
     *
     * <p>{@code step} is enlarged to a whole number of seconds when needed so that at most
     * {@code maxPoints} points are returned (Req 11.14); the returned series carries the step used.
     */
    public MetricSeries metric(String service, String promql, Instant start, Instant end, Duration step,
            int maxPoints) {
        Objects.requireNonNull(service, "service");
        requireRange(start, end);
        Objects.requireNonNull(step, "step");
        if (step.isNegative() || step.isZero()) {
            throw new IllegalArgumentException("step must be positive");
        }
        if (maxPoints < 1) {
            throw new IllegalArgumentException("maxPoints must be at least 1");
        }
        MetricKind kind = classify(promql);
        Duration effectiveStep = effectiveStep(Duration.between(start, end), step, maxPoints);
        List<MetricPoint> points = new ArrayList<>();
        for (Instant t = start; !t.isAfter(end) && points.size() < maxPoints; t = t.plus(effectiveStep)) {
            points.add(new MetricPoint(t, value(service, kind, t)));
        }
        return new MetricSeries(promql, effectiveStep, points);
    }

    /**
     * The smallest step, not below {@code requested}, with {@code floor(span/step) + 1 ≤ maxPoints}. An
     * enlarged step is rounded up to whole seconds.
     */
    static Duration effectiveStep(Duration span, Duration requested, int maxPoints) {
        if (maxPoints == 1) {
            return span.compareTo(requested) < 0 ? requested : span.plusNanos(1);
        }
        long intervals = maxPoints - 1L;
        if (requested.multipliedBy(intervals).compareTo(span) >= 0) {
            return requested;
        }
        Duration min = span.dividedBy(intervals);
        if (min.multipliedBy(intervals).compareTo(span) < 0) {
            min = min.plusNanos(1);
        }
        if (min.getNano() > 0) {
            min = Duration.ofSeconds(min.getSeconds() + 1);
        }
        return min.compareTo(requested) < 0 ? requested : min;
    }

    private double value(String service, MetricKind metric, Instant t) {
        boolean checkout = CHECKOUT_API.equals(service);
        switch (kind) {
            case PAYMENT_GATEWAY:
                if (metric == MetricKind.ERROR_RATIO && checkout) {
                    return after(ERROR_ONSET, t) ? ERROR_RATIO_INCIDENT : ERROR_RATIO_HEALTHY;
                }
                if (metric == MetricKind.LATENCY_P99 && PAYMENT_GATEWAY.equals(service)) {
                    return after(LATENCY_ONSET, t) ? GATEWAY_P99_INCIDENT_MS : GATEWAY_P99_HEALTHY_MS;
                }
                break;
            case BAD_DEPLOY:
                if (metric == MetricKind.ERROR_RATIO && checkout) {
                    return after(BAD_DEPLOY_ERROR_ONSET, t) ? ERROR_RATIO_INCIDENT : ERROR_RATIO_HEALTHY;
                }
                break;
            case DB_POOL_EXHAUSTION:
                if (metric == MetricKind.ERROR_RATIO && checkout) {
                    return after(DB_POOL_ONSET, t) ? DB_POOL_ERROR_RATIO : ERROR_RATIO_HEALTHY;
                }
                if (metric == MetricKind.LATENCY_P99 && checkout) {
                    return after(DB_POOL_ONSET, t) ? DB_POOL_P99_MS : BASELINE_P99_MS;
                }
                break;
            case NOISY_NEIGHBOUR:
                if (metric == MetricKind.LATENCY_P99 && checkout) {
                    return after(CPU_ONSET, t) ? NOISY_P99_MS : BASELINE_P99_MS;
                }
                if (metric == MetricKind.CPU && checkout) {
                    return after(CPU_ONSET, t) ? CPU_INCIDENT : CPU_HEALTHY;
                }
                break;
            default:
                throw new IllegalStateException("unknown scenario " + kind);
        }
        return baseline(service, metric);
    }

    /** True when {@code t} is at or after {@code a + offset}. */
    private boolean after(Duration offset, Instant t) {
        return !t.isBefore(anchor.plus(offset));
    }

    private static double baseline(String service, MetricKind metric) {
        return switch (metric) {
            case ERROR_RATIO -> ERROR_RATIO_HEALTHY;
            case LATENCY_P99 -> PAYMENT_GATEWAY.equals(service) ? GATEWAY_P99_HEALTHY_MS : BASELINE_P99_MS;
            case CPU -> CPU_HEALTHY;
            case REQUEST_RATE -> BASELINE_REQUEST_RATE;
        };
    }

    /**
     * Log search over {@code [start, end]} in time order.
     *
     * <p>Applies every {@code |= "text"} (or {@code |= `text`}) line filter in {@code logql} as a
     * case-insensitive substring match against the line {@code "<LEVEL> <message>"}; all filters must
     * match. Other LogQL syntax is ignored. At most {@code limit} entries are returned; {@code truncated}
     * is true when more matched.
     */
    public LogSearchResult logs(String service, String logql, Instant start, Instant end, int limit) {
        Objects.requireNonNull(service, "service");
        requireRange(start, end);
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        List<String> filters = lineFilters(logql);
        List<LogStream> streams = logStreams(service);

        long[] next = new long[streams.size()];
        for (int i = 0; i < streams.size(); i++) {
            next[i] = streams.get(i).firstIndexAtOrAfter(anchor, start);
        }
        List<LogEntry> entries = new ArrayList<>();
        while (true) {
            int pick = -1;
            Instant pickAt = null;
            for (int i = 0; i < streams.size(); i++) {
                LogStream stream = streams.get(i);
                if (next[i] >= stream.count()) {
                    continue;
                }
                Instant at = stream.at(anchor, next[i]);
                if (at.isAfter(end)) {
                    continue;
                }
                if (pickAt == null || at.isBefore(pickAt)) {
                    pick = i;
                    pickAt = at;
                }
            }
            if (pick < 0) {
                return new LogSearchResult(entries, false);
            }
            LogStream stream = streams.get(pick);
            next[pick]++;
            if (matches(stream.level() + " " + stream.message(), filters)) {
                if (entries.size() == limit) {
                    return new LogSearchResult(entries, true);
                }
                entries.add(new LogEntry(pickAt, stream.level(), stream.message()));
            }
        }
    }

    private List<LogStream> logStreams(String service) {
        if (!CHECKOUT_API.equals(service)) {
            return List.of(new LogStream(INFO_LOG_START, INFO_LOG_INTERVAL, Long.MAX_VALUE, "INFO",
                    BASELINE_INFO_MESSAGE));
        }
        LogStream info = new LogStream(INFO_LOG_START, INFO_LOG_INTERVAL, Long.MAX_VALUE, "INFO",
                CHECKOUT_INFO_MESSAGE);
        return switch (kind) {
            case PAYMENT_GATEWAY -> List.of(
                    new LogStream(ERROR_ONSET, TIMEOUT_LOG_INTERVAL, TIMEOUT_LOG_COUNT, "ERROR", TIMEOUT_MESSAGE), info);
            case BAD_DEPLOY -> List.of(
                    new LogStream(BAD_DEPLOY_ERROR_ONSET, NPE_LOG_INTERVAL, NPE_LOG_COUNT, "ERROR", NPE_MESSAGE), info);
            case DB_POOL_EXHAUSTION -> List.of(
                    new LogStream(DB_POOL_ONSET, HIKARI_LOG_INTERVAL, HIKARI_LOG_COUNT, "ERROR", HIKARI_MESSAGE), info);
            case NOISY_NEIGHBOUR -> List.of(
                    new LogStream(CPU_ONSET, Duration.ofSeconds(30), 32, "WARN", GC_MESSAGE),
                    new LogStream(CPU_ONSET.plusSeconds(5), Duration.ofSeconds(10), 96, "WARN", SLOW_REQUEST_MESSAGE),
                    info);
        };
    }

    /** The {@code |=} filter texts of a LogQL query, unescaped. */
    static List<String> lineFilters(String logql) {
        if (logql == null) {
            return List.of();
        }
        List<String> filters = new ArrayList<>();
        Matcher m = LINE_FILTER.matcher(logql);
        while (m.find()) {
            filters.add(m.group(1) != null ? m.group(1).replaceAll("\\\\(.)", "$1") : m.group(2));
        }
        return filters;
    }

    private static boolean matches(String line, List<String> filters) {
        String lower = line.toLowerCase(Locale.ROOT);
        for (String filter : filters) {
            if (!lower.contains(filter.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Problem traces starting in {@code [start, end]}: traces with an error, or lasting at least
     * {@code slowThreshold}. In the default scenario checkout-api and payment-gateway see the 60 failed
     * traces; in the others only checkout-api sees its 60 problem traces; other services have none. Trace IDs depend only on the trace index.
     */
    public TraceSearchResult traces(String service, Instant start, Instant end, Duration slowThreshold, int limit) {
        Objects.requireNonNull(service, "service");
        requireRange(start, end);
        Objects.requireNonNull(slowThreshold, "slowThreshold");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        TraceShape shape = switch (kind) {
            case PAYMENT_GATEWAY -> PAYMENT_GATEWAY_TRACES;
            case BAD_DEPLOY -> BAD_DEPLOY_TRACES;
            case DB_POOL_EXHAUSTION -> DB_POOL_TRACES;
            case NOISY_NEIGHBOUR -> NOISY_TRACES;
        };
        if (!shape.services().contains(service)) {
            return new TraceSearchResult(List.of(), false);
        }
        List<TraceRecord> traces = new ArrayList<>();
        for (int i = 0; i < FAILED_TRACE_COUNT; i++) {
            Instant at = anchor.plus(shape.onset()).plus(FAILED_TRACE_INTERVAL.multipliedBy(i));
            if (at.isBefore(start) || at.isAfter(end)) {
                continue;
            }
            TraceRecord trace = new TraceRecord(traceId(i), at, shape.duration(), shape.error(), shape.spans());
            if (!trace.error() && trace.duration().compareTo(slowThreshold) < 0) {
                continue;
            }
            if (traces.size() == limit) {
                return new TraceSearchResult(traces, true);
            }
            traces.add(trace);
        }
        return new TraceSearchResult(traces, false);
    }

    /** 32 lowercase hex characters: {@code 4d6f636b} ("Mock") followed by the 1-based index. */
    static String traceId(int index) {
        return String.format(Locale.ROOT, "4d6f636b%024x", index + 1L);
    }

    /** Deployments of {@code service} in {@code [since, until]}, newest first. */
    public List<ChangeRecord> changes(String service, Instant since, Instant until) {
        Objects.requireNonNull(service, "service");
        requireRange(since, until);
        if (!CHECKOUT_API.equals(service)) {
            return List.of();
        }
        ChangeRecord previous = new ChangeRecord(CHECKOUT_API, "v2.14.2", anchor.plus(PREVIOUS_DEPLOY_OFFSET),
                "dev-bob", "Add order history pagination", "https://github.com/example/checkout-api/pull/1398",
                List.of("src/main/java/com/example/checkout/orders/OrderHistoryController.java",
                        "src/main/resources/templates/order-history.html"));
        List<ChangeRecord> all = switch (kind) {
            case PAYMENT_GATEWAY -> List.of(
                    new ChangeRecord(CHECKOUT_API, "v2.14.3", anchor.plus(DEPLOY_OFFSET), "dev-alice",
                            "Update order confirmation copy", "https://github.com/example/checkout-api/pull/1423",
                            List.of("src/main/resources/messages.properties",
                                    "src/main/resources/templates/order-confirmation.html")),
                    previous);
            case BAD_DEPLOY -> List.of(
                    new ChangeRecord(CHECKOUT_API, "v2.14.3", anchor.plus(DEPLOY_OFFSET), "dev-alice",
                            "Refactor payment client retry policy", "https://github.com/example/checkout-api/pull/1427",
                            BAD_DEPLOY_FILES),
                    previous);
            case DB_POOL_EXHAUSTION, NOISY_NEIGHBOUR -> List.of(previous);
        };
        return all.stream()
                .filter(c -> !c.deployedAt().isBefore(since) && !c.deployedAt().isAfter(until))
                .toList();
    }

    private static void requireRange(Instant start, Instant end) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("start must not be after end");
        }
    }

    /**
     * The problem traces of a scenario: {@value #FAILED_TRACE_COUNT} traces every
     * {@code FAILED_TRACE_INTERVAL} from {@code a + onset}, seen by {@code services}.
     */
    private record TraceShape(List<String> services, Duration onset, Duration duration, boolean error,
            List<SpanRecord> spans) {
    }

    /** A periodic log stream: entry {@code k} is at {@code a + first + k·interval}, {@code k < count}. */
    private record LogStream(Duration first, Duration interval, long count, String level, String message) {

        Instant at(Instant anchor, long k) {
            return anchor.plus(first).plus(interval.multipliedBy(k));
        }

        long firstIndexAtOrAfter(Instant anchor, Instant start) {
            Duration diff = Duration.between(anchor.plus(first), start);
            if (diff.isNegative() || diff.isZero()) {
                return 0;
            }
            long k = diff.dividedBy(interval);
            return interval.multipliedBy(k).compareTo(diff) < 0 ? k + 1 : k;
        }
    }
}
