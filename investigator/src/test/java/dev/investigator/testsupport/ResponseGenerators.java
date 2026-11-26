package dev.investigator.testsupport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.investigator.integration.logs.LogEntry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * Seeded generators for backend response bodies used by the Phase 2 parsing properties (design "Correctness
 * Properties"). Each method takes the property's {@link SplittableRandom}, so a seed fixes the body and the
 * expected parse result.
 *
 * <p>Prometheus: {@link #prometheusMatrix} builds a {@code query_range} matrix body with a single series, as
 * the client requires (CP-61). Loki: {@link #lokiStreams} builds a {@code query_range} streams body. Tempo:
 * {@link #tempoSearch} builds an {@code /api/search} body. GitHub generators are added by a later task.
 */
public final class ResponseGenerators {

    /** Unix seconds of the demo alert time (2026-11-23T14:40:00Z); sample times are generated around it. */
    private static final long ANCHOR_SECONDS = DomainGenerators.ANCHOR.getEpochSecond();

    private static final String[] NON_FINITE = {"NaN", "+Inf", "-Inf"};
    private static final String[] LABEL_NAMES = {"service", "status", "instance", "job", "le"};

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String[] LOKI_LEVELS = {"debug", "info", "warn", "error", "fatal"};
    private static final String[] LOG_LINES = {
        "PaymentGatewayTimeoutException: call to payment-gateway timed out after 5000 ms",
        "checkout completed", "request completed", "", "  padded line  ", "quote \" and backslash \\ inside",
        "unicode: café ✓ 漢字", "tab\tand\nnewline", "{\"json\":\"line\",\"n\":1}"};
    private static final String[] TEMPO_SERVICES = {"checkout-api", "payment-gateway", "payment-provider"};
    private static final String[] TEMPO_OPERATIONS = {"POST /checkout", "POST /charge", "GET /health", "SELECT"};

    private ResponseGenerators() {
    }

    /**
     * One {@code [unix_seconds, "value"]} sample as written in the body, with the parsed form a correct
     * client must produce.
     *
     * @param at the sample time the timestamp text denotes (millisecond precision)
     * @param timeText the timestamp as a JSON number: an integer, a decimal, or scientific notation
     * @param valueText the value string: an integer, a decimal, scientific notation, {@code NaN} or {@code ±Inf}
     * @param value the number {@code valueText} denotes; {@code NaN} or infinite for the non-finite forms
     */
    public record PromSample(Instant at, String timeText, String valueText, double value) {

        public boolean finite() {
            return Double.isFinite(value);
        }
    }

    /**
     * A {@code query_range} success body and the samples it contains, in body order.
     *
     * @param body the JSON response body
     * @param samples every sample in the body, in the order written (not necessarily time order)
     */
    public record PromMatrix(String body, List<PromSample> samples) {

        public PromMatrix {
            samples = List.copyOf(samples);
        }
    }

    /**
     * A Prometheus {@code query_range} matrix body with one series.
     *
     * <p>The sample count is 0, 1, {@code maxPoints − 1}, {@code maxPoints}, {@code maxPoints + 1} or random
     * in {@code [0, maxPoints + 20]}. Timestamps are distinct, millisecond precision, usually ascending
     * (as Prometheus sends them) and shuffled in about 1 case in 5. In about half the cases every value is
     * finite; otherwise about 1 sample in 8 is {@code NaN}, {@code +Inf} or {@code -Inf}.
     */
    public static PromMatrix prometheusMatrix(SplittableRandom r, int maxPoints) {
        int count = sampleCount(r, maxPoints);
        boolean withNonFinite = r.nextBoolean();
        List<PromSample> samples = new ArrayList<>(count);
        long millis = (ANCHOR_SECONDS - r.nextLong(0, 86_400)) * 1000 + r.nextInt(1000);
        for (int i = 0; i < count; i++) {
            millis += 1 + r.nextLong(0, 60_000);
            samples.add(sample(r, millis, withNonFinite && r.nextInt(8) == 0));
        }
        if (r.nextInt(5) == 0) {
            Collections.shuffle(samples, new Random(r.nextLong()));
        }

        StringBuilder body = new StringBuilder("{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",")
                .append("\"result\":[{\"metric\":").append(labels(r)).append(",\"values\":[");
        for (int i = 0; i < samples.size(); i++) {
            PromSample s = samples.get(i);
            body.append(i == 0 ? "" : ",").append('[').append(s.timeText()).append(",\"").append(s.valueText())
                    .append("\"]");
        }
        body.append("]}]}}");
        return new PromMatrix(body.toString(), samples);
    }

    /**
     * A Loki {@code query_range} success body and the entries it contains.
     *
     * @param body the JSON response body
     * @param entries every entry in the body with the time, level and line a correct client must produce,
     *     sorted by time (times are distinct)
     */
    public record LokiStreams(String body, List<LogEntry> entries) {

        public LokiStreams {
            entries = List.copyOf(entries);
        }
    }

    /**
     * A Loki {@code query_range} streams body with 0 to 4 streams.
     *
     * <p>The total entry count is 0, 1, {@code limit − 1}, {@code limit}, {@code limit + 1} or random in
     * {@code [0, limit + 20]}, spread randomly over the streams. Times are distinct nanosecond timestamps;
     * each stream lists its values in ascending order (as {@code direction=forward} does), reversed in about
     * 1 stream in 5. A stream carries a lower-case {@code detected_level} label in about half the cases;
     * otherwise a value may carry {@code severity_text} as a third, structured-metadata element. Expected
     * levels are upper-cased, or {@code UNKNOWN} when neither is present.
     */
    public static LokiStreams lokiStreams(SplittableRandom r, int limit) {
        int count = sampleCount(r, limit);
        int streamCount = count == 0 ? r.nextInt(2) : r.nextInt(1, 5);
        List<List<long[]>> perStream = new ArrayList<>();
        for (int s = 0; s < streamCount; s++) {
            perStream.add(new ArrayList<>());
        }
        // Each value: {nanos, line index, metadata level index or -1}.
        long nanos = (ANCHOR_SECONDS - r.nextLong(0, 86_400)) * 1_000_000_000L + r.nextLong(0, 1_000_000_000L);
        for (int i = 0; i < count; i++) {
            nanos += 1 + r.nextLong(0, 60_000_000_000L);
            perStream.get(r.nextInt(streamCount)).add(new long[] {nanos, r.nextInt(LOG_LINES.length),
                    r.nextInt(3) == 0 ? r.nextInt(LOKI_LEVELS.length) : -1});
        }

        ObjectNode root = JSON.createObjectNode().put("status", "success");
        ObjectNode data = root.putObject("data").put("resultType", "streams");
        ArrayNode result = data.putArray("result");
        List<LogEntry> entries = new ArrayList<>(count);
        for (List<long[]> values : perStream) {
            ObjectNode stream = result.addObject();
            ObjectNode labels = stream.putObject("stream").put("service_name", "checkout-api");
            String streamLevel = r.nextBoolean() ? LOKI_LEVELS[r.nextInt(LOKI_LEVELS.length)] : null;
            if (streamLevel != null) {
                labels.put("detected_level", streamLevel);
            }
            if (r.nextInt(5) == 0) {
                Collections.reverse(values);
            }
            ArrayNode array = stream.putArray("values");
            for (long[] v : values) {
                String line = LOG_LINES[(int) v[1]];
                ArrayNode pair = array.addArray().add(Long.toString(v[0])).add(line);
                String level = streamLevel;
                if (v[2] >= 0) {
                    String metadataLevel = LOKI_LEVELS[(int) v[2]];
                    pair.addObject().put("severity_text", metadataLevel);
                    level = level == null ? metadataLevel : level;
                }
                entries.add(new LogEntry(Instant.ofEpochSecond(v[0] / 1_000_000_000L, v[0] % 1_000_000_000L),
                        level == null ? "UNKNOWN" : level.toUpperCase(Locale.ROOT), line));
            }
        }
        data.putObject("stats");
        entries.sort(Comparator.comparing(LogEntry::at));
        try {
            return new LokiStreams(JSON.writeValueAsString(root), entries);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * What a correct client must keep of one generated Tempo trace.
     *
     * @param traceId the {@code traceID}
     * @param start the {@code startTimeUnixNano} as an instant
     * @param error whether any of its spans has {@code status = error}
     * @param services the services of its distinct spans
     */
    public record TempoTrace(String traceId, Instant start, boolean error, Set<String> services) {

        public TempoTrace {
            services = Set.copyOf(services);
        }
    }

    /**
     * A Tempo search body and its traces.
     *
     * @param body the JSON response body
     * @param traces every trace in the body, sorted by start time (times are distinct)
     */
    public record TempoSearch(String body, List<TempoTrace> traces) {

        public TempoSearch {
            traces = List.copyOf(traces);
        }
    }

    /**
     * A Tempo {@code /api/search} body.
     *
     * <p>The trace count is 0, 1, {@code limit − 1}, {@code limit}, {@code limit + 1} or random in
     * {@code [0, limit + 20]}, in shuffled order (Tempo does not sort by start). Each trace has 0 to 3
     * {@code spanSets} (the older single {@code spanSet} in about 1 trace in 5) of 0 to 4 spans; a span may
     * repeat in a second span set with the same {@code spanID}. A span names its service with a
     * {@code service.name} or {@code resource.service.name} attribute, or omits it (the trace's
     * {@code rootServiceName} applies); its status is {@code error}, {@code ok} or absent. When there are no
     * traces the {@code traces} field is an empty array or omitted.
     */
    public static TempoSearch tempoSearch(SplittableRandom r, int limit) {
        int count = sampleCount(r, limit);
        ObjectNode root = JSON.createObjectNode();
        List<TempoTrace> expected = new ArrayList<>(count);
        List<ObjectNode> traces = new ArrayList<>(count);
        long nanos = (ANCHOR_SECONDS - r.nextLong(0, 86_400)) * 1_000_000_000L + r.nextLong(0, 1_000_000_000L);
        for (int i = 0; i < count; i++) {
            nanos += 1 + r.nextLong(0, 60_000_000_000L);
            String traceId = String.format("%016x%016x", r.nextLong(), i);
            String rootService = TEMPO_SERVICES[r.nextInt(TEMPO_SERVICES.length)];
            ObjectNode trace = JSON.createObjectNode().put("traceID", traceId).put("rootServiceName", rootService)
                    .put("rootTraceName", "POST /checkout").put("startTimeUnixNano", Long.toString(nanos));
            if (r.nextInt(4) != 0) {
                trace.put("durationMs", r.nextInt(1, 10_000));
            }
            Set<String> services = new HashSet<>();
            boolean error = false;
            List<ArrayNode> spanArrays = new ArrayList<>();
            int setCount = r.nextInt(4);
            boolean legacy = setCount > 0 && r.nextInt(5) == 0;
            ArrayNode sets = legacy ? null : trace.putArray("spanSets");
            for (int s = 0; s < (legacy ? 1 : setCount); s++) {
                ObjectNode set = legacy ? trace.putObject("spanSet") : sets.addObject();
                ArrayNode spans = set.putArray("spans");
                spanArrays.add(spans);
                int spanCount = r.nextInt(5);
                set.put("matched", spanCount);
                for (int k = 0; k < spanCount; k++) {
                    if (s > 0 && r.nextInt(4) == 0 && spanArrays.get(0).size() > 0) {
                        // Same span repeated in a later span set; it counts once.
                        spans.add(spanArrays.get(0).get(r.nextInt(spanArrays.get(0).size())).deepCopy());
                        continue;
                    }
                    ObjectNode span = spans.addObject().put("spanID", String.format("%08x%04x%04x", r.nextInt(), s, k))
                            .put("name", TEMPO_OPERATIONS[r.nextInt(TEMPO_OPERATIONS.length)])
                            .put("startTimeUnixNano", Long.toString(nanos + r.nextLong(0, 1_000_000_000L)))
                            .put("durationNanos", Long.toString(r.nextLong(1, 10_000_000_000L)));
                    ArrayNode attributes = span.putArray("attributes");
                    String service = rootService;
                    switch (r.nextInt(3)) {
                        case 0 -> {
                            service = TEMPO_SERVICES[r.nextInt(TEMPO_SERVICES.length)];
                            stringAttribute(attributes, "service.name", service);
                        }
                        case 1 -> {
                            service = TEMPO_SERVICES[r.nextInt(TEMPO_SERVICES.length)];
                            stringAttribute(attributes, "resource.service.name", service);
                        }
                        default -> {
                        }
                    }
                    services.add(service);
                    switch (r.nextInt(3)) {
                        case 0 -> {
                            stringAttribute(attributes, "status", "error");
                            error = true;
                        }
                        case 1 -> stringAttribute(attributes, "status", "ok");
                        default -> {
                        }
                    }
                    if (r.nextBoolean()) {
                        stringAttribute(attributes, "peer.service", TEMPO_SERVICES[r.nextInt(TEMPO_SERVICES.length)]);
                    }
                }
            }
            traces.add(trace);
            expected.add(new TempoTrace(traceId, Instant.ofEpochSecond(nanos / 1_000_000_000L,
                    nanos % 1_000_000_000L), error, services));
        }
        Collections.shuffle(traces, new Random(r.nextLong()));
        if (count > 0 || r.nextBoolean()) {
            root.putArray("traces").addAll(traces);
        }
        root.putObject("metrics").put("inspectedBytes", "1024").put("completedJobs", 1).put("totalJobs", 1);
        try {
            return new TempoSearch(JSON.writeValueAsString(root), expected);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void stringAttribute(ArrayNode attributes, String key, String value) {
        ObjectNode attribute = attributes.addObject().put("key", key);
        attribute.putObject("value").put("stringValue", value);
    }

    private static int sampleCount(SplittableRandom r, int maxPoints) {
        return switch (r.nextInt(6)) {
            case 0 -> 0;
            case 1 -> 1;
            case 2 -> maxPoints - 1;
            case 3 -> maxPoints;
            case 4 -> maxPoints + 1;
            default -> r.nextInt(maxPoints + 21);
        };
    }

    private static PromSample sample(SplittableRandom r, long epochMillis, boolean nonFinite) {
        Instant at = Instant.ofEpochMilli(epochMillis);
        BigDecimal seconds = BigDecimal.valueOf(epochMillis, 3);
        String timeText = switch (r.nextInt(3)) {
            // Whole seconds are written as an integer when the fraction is zero, as Prometheus does.
            case 0 -> seconds.stripTrailingZeros().toPlainString();
            case 1 -> seconds.toPlainString();
            default -> seconds.movePointLeft(9).toPlainString() + "E9";
        };
        if (nonFinite) {
            String text = NON_FINITE[r.nextInt(NON_FINITE.length)];
            double value = switch (text) {
                case "+Inf" -> Double.POSITIVE_INFINITY;
                case "-Inf" -> Double.NEGATIVE_INFINITY;
                default -> Double.NaN;
            };
            return new PromSample(at, timeText, text, value);
        }
        return switch (r.nextInt(4)) {
            case 0 -> {
                long whole = r.nextLong(-1_000_000, 1_000_001);
                yield new PromSample(at, timeText, Long.toString(whole), whole);
            }
            case 1 -> {
                double d = (r.nextBoolean() ? 1 : -1) * r.nextDouble() * Math.pow(10, r.nextInt(-12, 13));
                yield new PromSample(at, timeText, Double.toString(d), d);
            }
            case 2 -> {
                // Go-style scientific notation, e.g. 1.234e-07.
                BigDecimal mantissa = BigDecimal.valueOf(r.nextInt(-9999, 10_000), 3);
                int exponent = r.nextInt(-20, 21);
                String text = mantissa.toPlainString() + "e" + (exponent < 0 ? "-" : "+")
                        + String.format("%02d", Math.abs(exponent));
                yield new PromSample(at, timeText, text, mantissa.scaleByPowerOfTen(exponent).doubleValue());
            }
            default -> {
                String[] edges = {"0", "1", "0.5", Double.toString(Double.MAX_VALUE),
                        Double.toString(-Double.MAX_VALUE), Double.toString(Double.MIN_VALUE), "1e308", "2.5E-300"};
                String text = edges[r.nextInt(edges.length)];
                yield new PromSample(at, timeText, text, new BigDecimal(text).doubleValue());
            }
        };
    }

    /** {@code {}} (an aggregated series) or up to three safe {@code name: value} labels. */
    private static String labels(SplittableRandom r) {
        int n = r.nextInt(2) == 0 ? 0 : r.nextInt(1, 4);
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < n; i++) {
            json.append(i == 0 ? "" : ",").append('"').append(LABEL_NAMES[i]).append("\":\"v")
                    .append(r.nextInt(1000)).append('"');
        }
        return json.append('}').toString();
    }
}
