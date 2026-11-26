package dev.investigator.integration.metrics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.investigator.config.InvestigatorProperties;
import dev.investigator.integration.http.ConditionalOnIntegrationEndpoint;
import dev.investigator.integration.http.IntegrationCallException;
import dev.investigator.integration.http.RestClients;
import dev.investigator.tools.ObservabilityTools;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@link MetricsClient} backed by the Prometheus HTTP API {@code GET /api/v1/query_range} (Req 3.1).
 *
 * <p>Created only with the {@code real} profile and a non-blank
 * {@code investigator.integrations.metrics.base-url}; otherwise the mock fallback in
 * {@code IntegrationConfig} is used (Req 7.1, 7.2).
 *
 * <p>The step sent is {@link ObservabilityTools#metricStep} with the requested step as the minimum, so a
 * range yields at most {@code investigator.tools.max-metric-points} points (Req 3.2). The query must
 * return at most one series: aggregate with {@code sum(...)} or similar, as an unaggregated selector such
 * as {@code http_server_requests_seconds_count{service="checkout-api",status=~"5.."}} returns one series
 * per label set (CP-59, CP-61). HTTP errors, transport errors, timeouts and non-{@code success} responses
 * throw {@link IntegrationCallException} (Req 3.4).
 */
@Component
@Profile("real")
@ConditionalOnIntegrationEndpoint("metrics")
public class PrometheusMetricsClient implements MetricsClient {

    static final String INTEGRATION = "metrics";
    static final String QUERY_RANGE_PATH = "/api/v1/query_range";

    /** Hint returned to the model when a query yields more than one series (CP-61). */
    public static final String SINGLE_SERIES_HINT = "the query returned more than one series; aggregate to a"
            + " single series with sum(...), for example sum(rate(a[5m])) or a ratio of sums such as"
            + " sum(rate(a[5m])) / sum(rate(b[5m]))";

    /** Reads decimals exactly, so sub-second sample timestamps survive parsing. */
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private final RestClient rest;
    private final int maxPoints;

    public PrometheusMetricsClient(RestClients clients, InvestigatorProperties properties) {
        this.rest = clients.metrics();
        this.maxPoints = properties.tools().maxMetricPoints();
    }

    @Override
    public MetricSeries queryRange(String service, String promql, Instant start, Instant end, Duration step) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(promql, "promql");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(step, "step");
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("start must not be after end");
        }
        if (step.isNegative() || step.isZero()) {
            throw new IllegalArgumentException("step must be positive");
        }
        Duration used = ObservabilityTools.metricStep(Duration.between(start, end), step, maxPoints);
        Map<String, String> params = Map.of(
                "query", promql,
                "start", seconds(start),
                "end", seconds(end),
                "step", seconds(used));
        JsonNode body;
        try {
            body = rest.get()
                    .uri(b -> b.path(QUERY_RANGE_PATH)
                            .queryParam("query", "{query}")
                            .queryParam("start", "{start}")
                            .queryParam("end", "{end}")
                            .queryParam("step", "{step}")
                            .build(params))
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((request, response) -> readBody(response.getStatusCode(),
                            response.getBody().readAllBytes()));
        } catch (RestClientException e) {
            throw new IntegrationCallException(INTEGRATION, "query_range request failed", e);
        }
        return parse(body, promql, used, maxPoints);
    }

    /** Checks the HTTP status and parses the body as JSON. */
    private static JsonNode readBody(HttpStatusCode status, byte[] bytes) throws IOException {
        JsonNode json;
        try {
            json = JSON.readTree(bytes);
        } catch (JsonProcessingException e) {
            json = null;
        }
        if (status.isError()) {
            throw new IntegrationCallException(INTEGRATION,
                    "query_range returned HTTP " + status.value() + errorDetail(json));
        }
        if (json == null || !json.isObject()) {
            throw new IntegrationCallException(INTEGRATION, "query_range returned a body that is not a JSON object");
        }
        return json;
    }

    /**
     * Maps a {@code query_range} response body to a {@link MetricSeries} (Req 3.3, 3.5, 3.7).
     *
     * <p>Requires {@code status: "success"} and a {@code matrix} result with at most one series. An empty
     * result gives a series with no points. Each {@code [unix_seconds, "value"]} sample becomes one
     * {@link MetricPoint}, sorted by time and capped at {@code maxPoints}; samples whose value is
     * {@code NaN} or infinite (for example a 0/0 ratio with no traffic) are skipped.
     *
     * @throws IntegrationCallException when the body is not a usable success response
     */
    public static MetricSeries parse(JsonNode body, String query, Duration step, int maxPoints) {
        Objects.requireNonNull(body, "body");
        if (!"success".equals(body.path("status").asText())) {
            throw new IntegrationCallException(INTEGRATION, "query_range status is not success" + errorDetail(body));
        }
        JsonNode data = body.path("data");
        String resultType = data.path("resultType").asText();
        if (!"matrix".equals(resultType)) {
            throw new IntegrationCallException(INTEGRATION,
                    "query_range result type is '" + resultType + "', expected 'matrix'");
        }
        JsonNode result = data.path("result");
        if (!result.isArray()) {
            throw new IntegrationCallException(INTEGRATION, "query_range result is not an array");
        }
        if (result.isEmpty()) {
            return new MetricSeries(query, step, List.of());
        }
        if (result.size() > 1) {
            throw IntegrationCallException.withHint(INTEGRATION, "query returned " + result.size()
                    + " series; aggregate to a single series with sum(...)", SINGLE_SERIES_HINT);
        }
        JsonNode values = result.get(0).path("values");
        if (!values.isArray()) {
            throw new IntegrationCallException(INTEGRATION, "query_range series has no values array");
        }
        List<MetricPoint> points = new ArrayList<>(values.size());
        for (JsonNode sample : values) {
            if (!sample.isArray() || sample.size() != 2) {
                throw new IntegrationCallException(INTEGRATION, "query_range sample is not a [time, value] pair");
            }
            double value = sampleValue(sample.get(1));
            if (Double.isFinite(value)) {
                points.add(new MetricPoint(sampleTime(sample.get(0)), value));
            }
        }
        points.sort(Comparator.comparing(MetricPoint::at));
        if (points.size() > maxPoints) {
            points = points.subList(0, maxPoints);
        }
        return new MetricSeries(query, step, points);
    }

    private static Instant sampleTime(JsonNode node) {
        if (!node.isNumber()) {
            throw new IntegrationCallException(INTEGRATION, "query_range sample time is not a number");
        }
        BigDecimal scaled = node.decimalValue().setScale(9, RoundingMode.HALF_UP);
        BigDecimal whole = scaled.setScale(0, RoundingMode.FLOOR);
        return Instant.ofEpochSecond(whole.longValueExact(), scaled.subtract(whole).movePointRight(9).intValueExact());
    }

    private static double sampleValue(JsonNode node) {
        String text = node.asText();
        switch (text) {
            case "NaN":
                return Double.NaN;
            case "+Inf":
            case "Inf":
                return Double.POSITIVE_INFINITY;
            case "-Inf":
                return Double.NEGATIVE_INFINITY;
            default:
                try {
                    return Double.parseDouble(text);
                } catch (NumberFormatException e) {
                    throw new IntegrationCallException(INTEGRATION, "query_range sample value is not a number");
                }
        }
    }

    /** {@code ": <errorType>: <error>"} from a Prometheus error body, or empty. */
    private static String errorDetail(JsonNode json) {
        if (json == null) {
            return "";
        }
        String type = json.path("errorType").asText("");
        String error = json.path("error").asText("");
        if (type.isEmpty() && error.isEmpty()) {
            return "";
        }
        return ": " + type + (type.isEmpty() || error.isEmpty() ? "" : ": ") + error;
    }

    /** Unix seconds with up to nine decimals, as the Prometheus API accepts. */
    static String seconds(Instant instant) {
        return BigDecimal.valueOf(instant.getEpochSecond()).add(BigDecimal.valueOf(instant.getNano(), 9))
                .stripTrailingZeros().toPlainString();
    }

    /** A duration as seconds with up to nine decimals. */
    static String seconds(Duration duration) {
        return BigDecimal.valueOf(duration.getSeconds()).add(BigDecimal.valueOf(duration.getNano(), 9))
                .stripTrailingZeros().toPlainString();
    }
}
