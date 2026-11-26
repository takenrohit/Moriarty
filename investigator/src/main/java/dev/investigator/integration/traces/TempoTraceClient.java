package dev.investigator.integration.traces;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.investigator.config.InvestigatorProperties;
import dev.investigator.integration.http.ConditionalOnIntegrationEndpoint;
import dev.investigator.integration.http.IntegrationCallException;
import dev.investigator.integration.http.RestClients;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@link TraceClient} backed by the Tempo search API {@code GET /api/search} with a TraceQL query (Req 5.1).
 *
 * <p>Created only with the {@code real} profile and a non-blank {@code investigator.integrations.traces.base-url};
 * otherwise the mock fallback in {@code IntegrationConfig} is used (Req 7.1, 7.2).
 *
 * <p>The query selects spans of the service that have error status or last at least the slow threshold, and
 * asks Tempo to return their service, peer and status attributes:
 * {@code { resource.service.name = "<svc>" && (status = error || duration >= <slow>) }
 * | select(span.peer.service, span.server.address, status)}. Only the search response is used (no
 * {@code /api/traces/{id}} fetch): each trace's spans are its matched spans, at most {@value #SPANS_PER_SPANSET}
 * per span set (CP-63).
 *
 * <p>The client asks for {@code min(limit, trace-limit) + 1} traces, returns at most {@code min(limit, trace-limit)}
 * in start-time order and sets {@code truncated} when Tempo returned more (Req 5.2). HTTP errors, transport
 * errors, timeouts and malformed bodies throw {@link IntegrationCallException} (Req 5.4).
 */
@Component
@Profile("real")
@ConditionalOnIntegrationEndpoint("traces")
public class TempoTraceClient implements TraceClient {

    static final String INTEGRATION = "traces";
    static final String SEARCH_PATH = "/api/search";
    static final int SPANS_PER_SPANSET = 20;

    private static final String UNKNOWN_OPERATION = "unknown";
    private static final int MAX_ERROR_DETAIL = 200;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient rest;
    private final int traceLimit;

    public TempoTraceClient(RestClients clients, InvestigatorProperties properties) {
        this.rest = clients.traces();
        this.traceLimit = properties.tools().traceLimit();
    }

    @Override
    public TraceSearchResult findProblemTraces(String service, Instant start, Instant end, Duration slowThreshold,
            int limit) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(slowThreshold, "slowThreshold");
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("start must not be after end");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        int used = Math.min(limit, traceLimit);
        Map<String, String> params = Map.of(
                "q", traceql(service, slowThreshold),
                "start", Long.toString(start.getEpochSecond()),
                "end", Long.toString(ceilSeconds(end)),
                "limit", Integer.toString(used + 1),
                "spss", Integer.toString(SPANS_PER_SPANSET));
        JsonNode body;
        try {
            body = rest.get()
                    .uri(b -> b.path(SEARCH_PATH)
                            .queryParam("q", "{q}")
                            .queryParam("start", "{start}")
                            .queryParam("end", "{end}")
                            .queryParam("limit", "{limit}")
                            .queryParam("spss", "{spss}")
                            .build(params))
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((request, response) -> readBody(response.getStatusCode(),
                            response.getBody().readAllBytes()));
        } catch (RestClientException e) {
            throw new IntegrationCallException(INTEGRATION, "search request failed", e);
        }
        return parse(body, used);
    }

    /** The TraceQL query selecting error or slow spans of {@code service}, with the attributes the parser reads. */
    public static String traceql(String service, Duration slowThreshold) {
        String quoted = "\"" + service.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        return "{ resource.service.name = " + quoted + " && (status = error || duration >= "
                + durationLiteral(slowThreshold) + ") } | select(span.peer.service, span.server.address, status)";
    }

    private static String durationLiteral(Duration d) {
        long nanos = d.toNanos();
        return nanos % 1_000_000L == 0 ? (nanos / 1_000_000L) + "ms" : nanos + "ns";
    }

    private static long ceilSeconds(Instant instant) {
        return instant.getNano() == 0 ? instant.getEpochSecond() : instant.getEpochSecond() + 1;
    }

    /** Checks the HTTP status and parses the body as JSON. */
    private static JsonNode readBody(HttpStatusCode status, byte[] bytes) throws IOException {
        if (status.isError()) {
            throw new IntegrationCallException(INTEGRATION,
                    "search returned HTTP " + status.value() + errorDetail(bytes));
        }
        JsonNode json;
        try {
            json = JSON.readTree(bytes);
        } catch (JsonProcessingException e) {
            json = null;
        }
        if (json == null || !json.isObject()) {
            throw new IntegrationCallException(INTEGRATION, "search returned a body that is not a JSON object");
        }
        return json;
    }

    /**
     * Maps a Tempo search body to a {@link TraceSearchResult} (Req 5.3, 5.5, 5.6).
     *
     * <p>Each {@code traces[]} entry becomes one {@link TraceRecord}: {@code traceID}, {@code startTimeUnixNano},
     * {@code durationMs} (absent means under 1 ms, read as 0) and the distinct spans (by {@code spanID}) of
     * all its {@code spanSets[]} (or the older single {@code spanSet}). A span's service is its
     * {@code service.name} attribute, else the trace's {@code rootServiceName}; its operation is {@code name};
     * its peer is {@code peer.service}, else {@code server.address}; its error flag is {@code status = error}.
     * A trace is in error when any of its spans is. Traces are sorted by start time (then trace ID), the first
     * {@code limit} are kept, and {@code truncated} is true exactly when the body held more than {@code limit}.
     * A missing {@code traces} field is an empty result.
     *
     * @throws IntegrationCallException when the body is not a usable search response
     */
    public static TraceSearchResult parse(JsonNode body, int limit) {
        Objects.requireNonNull(body, "body");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        JsonNode traces = body.path("traces");
        if (traces.isMissingNode() || traces.isNull()) {
            return new TraceSearchResult(List.of(), false);
        }
        if (!traces.isArray()) {
            throw new IntegrationCallException(INTEGRATION, "search traces is not an array");
        }
        List<TraceRecord> records = new ArrayList<>();
        for (JsonNode trace : traces) {
            records.add(trace(trace));
        }
        records.sort(Comparator.comparing(TraceRecord::start).thenComparing(TraceRecord::traceId));
        boolean truncated = records.size() > limit;
        return new TraceSearchResult(truncated ? records.subList(0, limit) : records, truncated);
    }

    private static TraceRecord trace(JsonNode trace) {
        JsonNode id = trace.path("traceID");
        if (!id.isTextual() || id.asText().isBlank()) {
            throw new IntegrationCallException(INTEGRATION, "search trace has no traceID");
        }
        Instant start = nanosTime(trace.path("startTimeUnixNano"), "trace startTimeUnixNano");
        JsonNode durationMs = trace.path("durationMs");
        if (!durationMs.isMissingNode() && !durationMs.canConvertToLong()) {
            throw new IntegrationCallException(INTEGRATION, "search trace durationMs is not a number");
        }
        String rootService = trace.path("rootServiceName").asText(null);
        List<JsonNode> spanSets = new ArrayList<>();
        trace.path("spanSets").forEach(spanSets::add);
        if (spanSets.isEmpty() && trace.path("spanSet").isObject()) {
            spanSets.add(trace.path("spanSet"));
        }
        Map<String, SpanRecord> spans = new LinkedHashMap<>();
        for (JsonNode spanSet : spanSets) {
            for (JsonNode span : spanSet.path("spans")) {
                String spanId = span.path("spanID").asText("");
                SpanRecord record = span(span, rootService);
                spans.putIfAbsent(spanId.isEmpty() ? "#" + spans.size() : spanId, record);
            }
        }
        boolean error = spans.values().stream().anyMatch(SpanRecord::error);
        return new TraceRecord(id.asText(), start, Duration.ofMillis(durationMs.asLong(0)), error,
                List.copyOf(spans.values()));
    }

    private static SpanRecord span(JsonNode span, String rootService) {
        Map<String, String> attributes = attributes(span.path("attributes"));
        String service = attributes.getOrDefault("service.name", rootService);
        String operation = span.path("name").asText("");
        if (operation.isBlank()) {
            operation = attributes.getOrDefault("name", UNKNOWN_OPERATION);
        }
        String peer = attributes.getOrDefault("peer.service", attributes.get("server.address"));
        JsonNode durationNanos = span.path("durationNanos");
        long nanos;
        try {
            nanos = durationNanos.isMissingNode() ? 0 : Long.parseLong(durationNanos.asText());
        } catch (NumberFormatException e) {
            throw new IntegrationCallException(INTEGRATION, "search span durationNanos is not a number");
        }
        boolean error = "error".equals(attributes.getOrDefault("status", "").toLowerCase(Locale.ROOT));
        return new SpanRecord(service, operation, peer, Duration.ofNanos(nanos), error);
    }

    /** OTLP-style {@code [{key, value: {stringValue|intValue|...}}]} as key → text, scope prefixes removed. */
    private static Map<String, String> attributes(JsonNode array) {
        Map<String, String> result = new HashMap<>();
        for (JsonNode attribute : array) {
            String key = attribute.path("key").asText("");
            for (String prefix : List.of("resource.", "span.")) {
                if (key.startsWith(prefix)) {
                    key = key.substring(prefix.length());
                }
            }
            JsonNode value = attribute.path("value");
            String text = value.isObject() && value.size() > 0 ? value.elements().next().asText() : value.asText();
            if (!key.isEmpty() && text != null && !text.isEmpty()) {
                result.putIfAbsent(key, text);
            }
        }
        return result;
    }

    private static Instant nanosTime(JsonNode node, String what) {
        long nanos;
        try {
            nanos = Long.parseLong(node.asText());
        } catch (NumberFormatException e) {
            nanos = -1;
        }
        if (node.isMissingNode() || nanos < 0) {
            throw new IntegrationCallException(INTEGRATION, "search " + what + " is not a nanosecond timestamp");
        }
        return Instant.ofEpochSecond(nanos / 1_000_000_000L, nanos % 1_000_000_000L);
    }

    /** {@code ": <first 200 characters of the body>"}, or empty for an empty body. */
    private static String errorDetail(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8).strip();
        if (text.isEmpty()) {
            return "";
        }
        return ": " + (text.length() > MAX_ERROR_DETAIL ? text.substring(0, MAX_ERROR_DETAIL) + "..." : text);
    }
}
