package dev.investigator.integration.logs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.investigator.config.InvestigatorProperties;
import dev.investigator.integration.http.ConditionalOnIntegrationEndpoint;
import dev.investigator.integration.http.IntegrationCallException;
import dev.investigator.integration.http.RestClients;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@link LogClient} backed by the Loki HTTP API {@code GET /loki/api/v1/query_range} (Req 4.1).
 *
 * <p>Created only with the {@code real} profile and a non-blank {@code investigator.integrations.logs.base-url};
 * otherwise the mock fallback in {@code IntegrationConfig} is used (Req 7.1, 7.2).
 *
 * <p>The client builds the stream selector itself, {@code {<label>="<service>"}}, where the label is
 * {@code investigator.integrations.logs.service-label} (default {@code service_name}, the label Loki gives
 * OTLP logs). The model's query contributes only its pipeline: a leading stream selector, if any, is
 * dropped, and the rest must start with a line filter or another pipeline stage ({@code |} or {@code !}).
 * So {@code {service="checkout-api"} |= "timeout"} becomes {@code {service_name="checkout-api"} |= "timeout"}.
 *
 * <p>The client asks Loki for {@code min(limit, log-fetch-limit) + 1} entries in {@code forward} direction,
 * returns at most {@code min(limit, log-fetch-limit)} in time order, and sets {@code truncated} when Loki
 * returned more (Req 4.2, 4.3). HTTP errors, transport errors, timeouts and non-{@code success} responses
 * throw {@link IntegrationCallException}; an HTTP 400 (usually a LogQL parse error) carries
 * {@link #LOGQL_HINT} (Req 4.5).
 */
@Component
@Profile("real")
@ConditionalOnIntegrationEndpoint("logs")
public class LokiLogClient implements LogClient {

    static final String INTEGRATION = "logs";
    static final String QUERY_RANGE_PATH = "/loki/api/v1/query_range";

    /** Hint returned to the model when its LogQL cannot be used (constant text, never the query). */
    public static final String LOGQL_HINT = "pass LogQL line filters such as |= \"timeout\" or"
            + " {service=\"checkout-api\"} |= \"timeout\"; the service selector is set from the service argument"
            + " and metric queries such as count_over_time(...) are not supported";

    /** Stream labels (or structured metadata) holding the level, in order of preference. */
    private static final List<String> LEVEL_LABELS = List.of("detected_level", "severity_text", "level");
    static final String UNKNOWN_LEVEL = "UNKNOWN";

    private static final Pattern LABEL_NAME = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");
    private static final int MAX_ERROR_DETAIL = 200;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient rest;
    private final int fetchLimit;
    private final String serviceLabel;

    public LokiLogClient(RestClients clients, InvestigatorProperties properties,
            @Value("${investigator.integrations.logs.service-label:service_name}") String serviceLabel) {
        if (serviceLabel == null || !LABEL_NAME.matcher(serviceLabel.strip()).matches()) {
            throw new IllegalArgumentException(
                    "investigator.integrations.logs.service-label must be a Loki label name");
        }
        this.rest = clients.logs();
        this.fetchLimit = properties.tools().logFetchLimit();
        this.serviceLabel = serviceLabel.strip();
    }

    @Override
    public LogSearchResult search(String service, String logql, Instant start, Instant end, int limit) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(logql, "logql");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("start must not be after end");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        int used = Math.min(limit, fetchLimit);
        Map<String, String> params = Map.of(
                "query", logql(serviceLabel, service, logql),
                "start", nanos(start),
                "end", nanos(end),
                "limit", Integer.toString(used + 1));
        JsonNode body;
        try {
            body = rest.get()
                    .uri(b -> b.path(QUERY_RANGE_PATH)
                            .queryParam("query", "{query}")
                            .queryParam("start", "{start}")
                            .queryParam("end", "{end}")
                            .queryParam("limit", "{limit}")
                            .queryParam("direction", "forward")
                            .build(params))
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((request, response) -> readBody(response.getStatusCode(),
                            response.getBody().readAllBytes()));
        } catch (RestClientException e) {
            throw new IntegrationCallException(INTEGRATION, "query_range request failed", e);
        }
        return parse(body, used);
    }

    /**
     * The LogQL sent to Loki: {@code {<label>="<service>"}} followed by the pipeline of {@code query}
     * (its leading stream selector, if any, removed).
     *
     * @throws IntegrationCallException with {@link #LOGQL_HINT} when {@code query} has no usable pipeline
     */
    public static String logql(String serviceLabel, String service, String query) {
        String selector = "{" + serviceLabel + "=\"" + service.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        String rest = unescapeQuotes(query.strip());
        if (rest.startsWith("{")) {
            int close = selectorEnd(rest);
            if (close < 0) {
                throw IntegrationCallException.withHint(INTEGRATION, "LogQL stream selector is not closed", LOGQL_HINT);
            }
            rest = rest.substring(close + 1).strip();
        }
        if (rest.isEmpty()) {
            return selector;
        }
        if (rest.charAt(0) != '|' && rest.charAt(0) != '!') {
            throw IntegrationCallException.withHint(INTEGRATION,
                    "LogQL query is not a stream selector with line filters", LOGQL_HINT);
        }
        return selector + " " + rest;
    }

    /**
     * Undoes one level of JSON escaping when every double quote in {@code query} is escaped, as in
     * {@code {service=\"checkout-api\"} |= \"timeout\"} (seen from Bedrock Nova tool arguments). A query
     * with at least one unescaped quote is valid LogQL quoting and is returned unchanged.
     */
    static String unescapeQuotes(String query) {
        if (!query.contains("\\\"")) {
            return query;
        }
        for (int i = 0; i < query.length(); i++) {
            if (query.charAt(i) == '"' && (i == 0 || query.charAt(i - 1) != '\\')) {
                return query;
            }
        }
        return query.replace("\\\"", "\"");
    }

    /** Index of the closing brace of the selector that starts at index 0, skipping quoted text; -1 if none. */
    private static int selectorEnd(String query) {
        char quote = 0;
        for (int i = 1; i < query.length(); i++) {
            char c = query.charAt(i);
            if (quote != 0) {
                if (c == '\\' && quote == '"') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '`') {
                quote = c;
            } else if (c == '}') {
                return i;
            }
        }
        return -1;
    }

    /** Checks the HTTP status and parses the body as JSON. */
    private static JsonNode readBody(HttpStatusCode status, byte[] bytes) throws IOException {
        if (status.isError()) {
            String message = "query_range returned HTTP " + status.value() + errorDetail(bytes);
            if (status.value() == 400) {
                throw IntegrationCallException.withHint(INTEGRATION, message, LOGQL_HINT);
            }
            throw new IntegrationCallException(INTEGRATION, message);
        }
        JsonNode json;
        try {
            json = JSON.readTree(bytes);
        } catch (JsonProcessingException e) {
            json = null;
        }
        if (json == null || !json.isObject()) {
            throw new IntegrationCallException(INTEGRATION, "query_range returned a body that is not a JSON object");
        }
        return json;
    }

    /**
     * Maps a {@code query_range} streams body to a {@link LogSearchResult} (Req 4.2, 4.3, 4.7).
     *
     * <p>Requires {@code status: "success"} and a {@code streams} result. Every
     * {@code ["<unix_nanoseconds>", "<line>"]} value of every stream (optionally with a third
     * structured-metadata object) becomes one {@link LogEntry}; entries are sorted by time (stable, so equal
     * times keep body order) and the first {@code limit} are kept. {@code truncated} is true exactly when
     * the body held more than {@code limit} entries. The level is the first of {@code detected_level},
     * {@code severity_text} or {@code level} found in the value's metadata or the stream labels, upper-cased,
     * else {@value #UNKNOWN_LEVEL}.
     *
     * @throws IntegrationCallException when the body is not a usable success response
     */
    public static LogSearchResult parse(JsonNode body, int limit) {
        Objects.requireNonNull(body, "body");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        if (!"success".equals(body.path("status").asText())) {
            throw new IntegrationCallException(INTEGRATION, "query_range status is not success");
        }
        JsonNode data = body.path("data");
        String resultType = data.path("resultType").asText();
        if (!"streams".equals(resultType)) {
            throw IntegrationCallException.withHint(INTEGRATION,
                    "query_range result type is '" + resultType + "', expected 'streams'", LOGQL_HINT);
        }
        JsonNode result = data.path("result");
        if (!result.isArray()) {
            throw new IntegrationCallException(INTEGRATION, "query_range result is not an array");
        }
        List<LogEntry> entries = new ArrayList<>();
        for (JsonNode stream : result) {
            JsonNode labels = stream.path("stream");
            JsonNode values = stream.path("values");
            if (!values.isArray()) {
                throw new IntegrationCallException(INTEGRATION, "query_range stream has no values array");
            }
            for (JsonNode value : values) {
                if (!value.isArray() || value.size() < 2 || !value.get(1).isTextual()) {
                    throw new IntegrationCallException(INTEGRATION, "query_range value is not a [time, line] pair");
                }
                entries.add(new LogEntry(entryTime(value.get(0)), level(value.path(2), labels),
                        value.get(1).asText()));
            }
        }
        entries.sort(Comparator.comparing(LogEntry::at));
        boolean truncated = entries.size() > limit;
        return new LogSearchResult(truncated ? entries.subList(0, limit) : entries, truncated);
    }

    private static Instant entryTime(JsonNode node) {
        long nanos;
        try {
            nanos = Long.parseLong(node.asText());
        } catch (NumberFormatException e) {
            nanos = -1;
        }
        if (!node.isTextual() || nanos < 0) {
            throw new IntegrationCallException(INTEGRATION, "query_range value time is not a nanosecond timestamp");
        }
        return Instant.ofEpochSecond(nanos / 1_000_000_000L, nanos % 1_000_000_000L);
    }

    private static String level(JsonNode metadata, JsonNode labels) {
        for (String name : LEVEL_LABELS) {
            for (JsonNode source : List.of(metadata, labels)) {
                String text = source.path(name).asText("").strip();
                if (!text.isEmpty()) {
                    return text.toUpperCase(Locale.ROOT);
                }
            }
        }
        return UNKNOWN_LEVEL;
    }

    /** {@code ": <first 200 characters of the body>"}, or empty for an empty body. */
    private static String errorDetail(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8).strip();
        if (text.isEmpty()) {
            return "";
        }
        return ": " + (text.length() > MAX_ERROR_DETAIL ? text.substring(0, MAX_ERROR_DETAIL) + "..." : text);
    }

    /** Unix nanoseconds, as the Loki API accepts. */
    static String nanos(Instant instant) {
        return Long.toString(Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L),
                instant.getNano()));
    }
}
