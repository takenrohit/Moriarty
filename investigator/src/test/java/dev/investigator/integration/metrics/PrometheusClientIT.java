package dev.investigator.integration.metrics;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import dev.investigator.config.IntegrationProperties;
import dev.investigator.config.IntegrationProperties.Endpoint;
import dev.investigator.config.InvestigatorProperties;
import dev.investigator.integration.http.IntegrationCallException;
import dev.investigator.integration.http.RestClients;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * {@link PrometheusMetricsClient} against a WireMock Prometheus serving recorded-style
 * {@code /api/v1/query_range} bodies: request shape and credentials, a matrix result, a non-success
 * response, an empty result, an HTTP 5xx and a read timeout (Req 3.1, 3.4, 3.5, 14.4). No real
 * credentials are needed; WireMock runs in-process. Tagged {@code containers} like every {@code *IT}
 * (CP-55).
 */
@Tag("containers")
class PrometheusClientIT {

    private static final WireMockServer PROMETHEUS = new WireMockServer(options().dynamicPort());

    private static final String PROMQL =
            "sum(rate(http_server_requests_seconds_count{service=\"checkout-api\",status=~\"5..\"}[1m]))";
    private static final Instant START = Instant.parse("2026-11-23T14:30:00Z");
    private static final Instant END = Instant.parse("2026-11-23T14:40:00Z");
    private static final Duration STEP = Duration.ofSeconds(15);

    /** Recorded from a Prometheus 2.x/3.x {@code query_range} on an aggregated rate (labels dropped by sum). */
    private static final String MATRIX_BODY = """
            {"status":"success","data":{"resultType":"matrix","result":[{"metric":{},"values":[
              [1795444200,"0.0021"],[1795444215,"0.0019"],[1795444230,"0.4166666666666667"],
              [1795444245,"0.8333333333333334"],[1795444260,"NaN"]]}],
             "analysis":{}}}
            """;

    private static final String EMPTY_BODY = """
            {"status":"success","data":{"resultType":"matrix","result":[]}}
            """;

    /** Recorded from Prometheus for an unparsable query (HTTP 400). */
    private static final String BAD_DATA_BODY = """
            {"status":"error","errorType":"bad_data","error":"invalid parameter \\"query\\": 1:5: parse error: \
            unexpected <op:(>"}
            """;

    private static final InvestigatorProperties PROPERTIES = new Binder(new MapConfigurationPropertySource())
            .bindOrCreate("investigator", InvestigatorProperties.class);

    @BeforeAll
    static void start() {
        PROMETHEUS.start();
    }

    @AfterAll
    static void stop() {
        PROMETHEUS.stop();
    }

    @BeforeEach
    void reset() {
        PROMETHEUS.resetAll();
    }

    @Test
    void queryRangeSendsQueryParamsAndBearerTokenAndMapsTheMatrix() {
        stub(json(200, MATRIX_BODY));

        MetricSeries series = client(new Endpoint(PROMETHEUS.baseUrl(), "test-token", null, null,
                Duration.ofSeconds(2), Duration.ofSeconds(10)))
                .queryRange("checkout-api", PROMQL, START, END, STEP);

        PROMETHEUS.verify(1, getRequestedFor(urlPathEqualTo("/api/v1/query_range"))
                .withQueryParam("query", equalTo(PROMQL))
                .withQueryParam("start", equalTo("1795444200"))
                .withQueryParam("end", equalTo("1795444800"))
                .withQueryParam("step", equalTo("15"))
                .withHeader("Authorization", equalTo("Bearer test-token")));
        assertThat(series.query()).isEqualTo(PROMQL);
        assertThat(series.step()).isEqualTo(STEP);
        // The NaN sample (a 0/0 ratio) is skipped.
        assertThat(series.points()).containsExactly(
                new MetricPoint(Instant.parse("2026-11-23T14:30:00Z"), 0.0021),
                new MetricPoint(Instant.parse("2026-11-23T14:30:15Z"), 0.0019),
                new MetricPoint(Instant.parse("2026-11-23T14:30:30Z"), 0.4166666666666667),
                new MetricPoint(Instant.parse("2026-11-23T14:30:45Z"), 0.8333333333333334));
    }

    @Test
    void basicCredentialIsSentWhenConfigured() {
        stub(json(200, MATRIX_BODY));

        client(new Endpoint(PROMETHEUS.baseUrl(), null, "prom-user", "prom-pass",
                Duration.ofSeconds(2), Duration.ofSeconds(10)))
                .queryRange("checkout-api", PROMQL, START, END, STEP);

        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("prom-user:prom-pass".getBytes(StandardCharsets.UTF_8));
        PROMETHEUS.verify(1, getRequestedFor(urlPathEqualTo("/api/v1/query_range"))
                .withHeader("Authorization", equalTo(expected)));
    }

    @Test
    void emptyResultGivesASeriesWithNoPointsAndNoCredential() {
        stub(json(200, EMPTY_BODY));

        MetricSeries series = client(noCredential(Duration.ofSeconds(10)))
                .queryRange("checkout-api", PROMQL, START, END, STEP);

        assertThat(series.points()).isEmpty();
        assertThat(series.step()).isEqualTo(STEP);
        PROMETHEUS.verify(1, getRequestedFor(urlPathEqualTo("/api/v1/query_range"))
                .withHeader("Authorization", absent()));
    }

    @Test
    void errorStatusWithHttp400Throws() {
        stub(json(400, BAD_DATA_BODY));

        assertThatThrownBy(() -> client(noCredential(Duration.ofSeconds(10)))
                .queryRange("checkout-api", PROMQL, START, END, STEP))
                .isInstanceOfSatisfying(IntegrationCallException.class,
                        e -> assertThat(e.integration()).isEqualTo("metrics"))
                .hasMessageContaining("HTTP 400")
                .hasMessageContaining("bad_data");
    }

    @Test
    void errorStatusWithHttp200Throws() {
        stub(json(200, """
                {"status":"error","errorType":"execution","error":"query processing would load too many samples"}
                """));

        assertThatThrownBy(() -> client(noCredential(Duration.ofSeconds(10)))
                .queryRange("checkout-api", PROMQL, START, END, STEP))
                .isInstanceOf(IntegrationCallException.class)
                .hasMessageContaining("status is not success")
                .hasMessageContaining("execution");
    }

    @Test
    void http5xxThrows() {
        stub(aResponse().withStatus(503).withHeader("Content-Type", "text/plain")
                .withBody("Service Unavailable"));

        assertThatThrownBy(() -> client(noCredential(Duration.ofSeconds(10)))
                .queryRange("checkout-api", PROMQL, START, END, STEP))
                .isInstanceOf(IntegrationCallException.class)
                .hasMessageContaining("HTTP 503");
    }

    @Test
    void readTimeoutThrows() {
        stub(json(200, MATRIX_BODY).withFixedDelay(2_000));

        assertThatThrownBy(() -> client(noCredential(Duration.ofMillis(200)))
                .queryRange("checkout-api", PROMQL, START, END, STEP))
                .isInstanceOf(IntegrationCallException.class)
                .hasMessageContaining("query_range request failed");
    }

    private static void stub(ResponseDefinitionBuilder response) {
        PROMETHEUS.stubFor(get(urlPathEqualTo("/api/v1/query_range")).willReturn(response));
    }

    private static ResponseDefinitionBuilder json(int status, String body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
    }

    private static Endpoint noCredential(Duration readTimeout) {
        return new Endpoint(PROMETHEUS.baseUrl(), null, null, null, Duration.ofSeconds(2), readTimeout);
    }

    private static PrometheusMetricsClient client(Endpoint metrics) {
        Endpoint unset = new Endpoint(null, null, null, null, Duration.ofSeconds(2), Duration.ofSeconds(10));
        return new PrometheusMetricsClient(
                new RestClients(new IntegrationProperties(metrics, unset, unset, unset)), PROPERTIES);
    }
}
