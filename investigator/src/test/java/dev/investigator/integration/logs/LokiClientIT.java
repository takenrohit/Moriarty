package dev.investigator.integration.logs;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
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
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * {@link LokiLogClient} against a WireMock Loki serving recorded-style {@code /loki/api/v1/query_range}
 * bodies: request shape (LogQL selector on {@code service_name}, nanosecond range, {@code limit + 1},
 * forward direction), streams merged in time order with levels, the truncation boundary, and HTTP errors
 * (Req 4.1, 4.2, 4.5, 14.4). No credentials; WireMock runs in-process. Tagged {@code containers} like every
 * {@code *IT} (CP-55).
 */
@Tag("containers")
class LokiClientIT {

    private static final WireMockServer LOKI = new WireMockServer(options().dynamicPort());

    private static final Instant START = Instant.parse("2026-11-23T14:30:00Z");
    private static final Instant END = Instant.parse("2026-11-23T14:40:00Z");
    private static final String MODEL_QUERY = "{service=\"checkout-api\"} |= \"PaymentGatewayTimeoutException\"";
    private static final String SENT_QUERY = "{service_name=\"checkout-api\"} |= \"PaymentGatewayTimeoutException\"";

    /**
     * Shaped like Loki 3.x for OTLP-ingested logs: structured metadata merged into the stream labels, one
     * stream per label set, values {@code [unix_ns, line]} ascending within a stream.
     */
    private static final String STREAMS_BODY = """
            {"status":"success","data":{"resultType":"streams","result":[
              {"stream":{"service_name":"checkout-api","detected_level":"error","severity_text":"ERROR",
                         "scope_name":"dev.mock.checkout.CheckoutController"},
               "values":[
                 ["1795444080000000000","PaymentGatewayTimeoutException: call to payment-gateway timed out after 5000 ms"],
                 ["1795444140500000000","PaymentGatewayTimeoutException: call to payment-gateway timed out after 5003 ms"]]},
              {"stream":{"service_name":"checkout-api","severity_text":"WARN"},
               "values":[["1795444110000000001","retrying PaymentGatewayTimeoutException once"]]}],
             "stats":{"summary":{"totalEntriesReturned":3}}}}
            """;

    private static final InvestigatorProperties PROPERTIES = new Binder(new MapConfigurationPropertySource())
            .bindOrCreate("investigator", InvestigatorProperties.class);

    @BeforeAll
    static void start() {
        LOKI.start();
    }

    @AfterAll
    static void stop() {
        LOKI.stop();
    }

    @BeforeEach
    void reset() {
        LOKI.resetAll();
    }

    @Test
    void queryRangeSendsSelectorRangeAndLimitAndMergesStreamsInTimeOrder() {
        stub(json(200, STREAMS_BODY));

        LogSearchResult result = client().search("checkout-api", MODEL_QUERY, START, END, 500);

        LOKI.verify(1, getRequestedFor(urlPathEqualTo("/loki/api/v1/query_range"))
                .withQueryParam("query", equalTo(SENT_QUERY))
                .withQueryParam("start", equalTo("1795444200000000000"))
                .withQueryParam("end", equalTo("1795444800000000000"))
                .withQueryParam("limit", equalTo("501"))
                .withQueryParam("direction", equalTo("forward"))
                .withHeader("Authorization", absent()));
        assertThat(result.truncated()).isFalse();
        assertThat(result.entries()).containsExactly(
                new LogEntry(Instant.parse("2026-11-23T14:28:00Z"), "ERROR",
                        "PaymentGatewayTimeoutException: call to payment-gateway timed out after 5000 ms"),
                new LogEntry(Instant.parse("2026-11-23T14:28:30.000000001Z"), "WARN",
                        "retrying PaymentGatewayTimeoutException once"),
                new LogEntry(Instant.parse("2026-11-23T14:29:00.500Z"), "ERROR",
                        "PaymentGatewayTimeoutException: call to payment-gateway timed out after 5003 ms"));
    }

    @Test
    void bareLineFilterGetsTheServiceSelector() {
        stub(json(200, STREAMS_BODY));

        client().search("checkout-api", "|= \"timeout\"", START, END, 10);

        LOKI.verify(1, getRequestedFor(urlPathEqualTo("/loki/api/v1/query_range"))
                .withQueryParam("query", equalTo("{service_name=\"checkout-api\"} |= \"timeout\""))
                .withQueryParam("limit", equalTo("11")));
    }

    @Test
    void exactlyLimitEntriesIsNotTruncated() {
        stub(json(200, STREAMS_BODY));

        LogSearchResult result = client().search("checkout-api", MODEL_QUERY, START, END, 3);

        LOKI.verify(1, getRequestedFor(urlPathEqualTo("/loki/api/v1/query_range"))
                .withQueryParam("limit", equalTo("4")));
        assertThat(result.truncated()).isFalse();
        assertThat(result.entries()).hasSize(3);
    }

    @Test
    void oneEntryMoreThanTheLimitIsTruncatedToTheEarliest() {
        stub(json(200, STREAMS_BODY));

        LogSearchResult result = client().search("checkout-api", MODEL_QUERY, START, END, 2);

        LOKI.verify(1, getRequestedFor(urlPathEqualTo("/loki/api/v1/query_range"))
                .withQueryParam("limit", equalTo("3")));
        assertThat(result.truncated()).isTrue();
        assertThat(result.entries()).extracting(LogEntry::at).containsExactly(
                Instant.parse("2026-11-23T14:28:00Z"), Instant.parse("2026-11-23T14:28:30.000000001Z"));
    }

    @Test
    void emptyResultIsNotTruncated() {
        stub(json(200, """
                {"status":"success","data":{"resultType":"streams","result":[],"stats":{}}}
                """));

        LogSearchResult result = client().search("checkout-api", MODEL_QUERY, START, END, 500);

        assertThat(result.entries()).isEmpty();
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void http400ThrowsWithTheLogqlHint() {
        stub(aResponse().withStatus(400).withHeader("Content-Type", "text/plain; charset=utf-8")
                .withBody("parse error at line 1, col 26: syntax error: unexpected IDENTIFIER"));

        assertThatThrownBy(() -> client().search("checkout-api", MODEL_QUERY, START, END, 500))
                .isInstanceOfSatisfying(IntegrationCallException.class, e -> {
                    assertThat(e.integration()).isEqualTo("logs");
                    assertThat(e.hint()).isEqualTo(LokiLogClient.LOGQL_HINT);
                })
                .hasMessageContaining("HTTP 400")
                .hasMessageContaining("parse error");
    }

    @Test
    void http5xxThrowsWithoutHint() {
        stub(aResponse().withStatus(503).withHeader("Content-Type", "text/plain").withBody("Service Unavailable"));

        assertThatThrownBy(() -> client().search("checkout-api", MODEL_QUERY, START, END, 500))
                .isInstanceOfSatisfying(IntegrationCallException.class, e -> assertThat(e.hint()).isNull())
                .hasMessageContaining("HTTP 503");
    }

    @Test
    void metricQueryIsRejectedBeforeCallingLoki() {
        assertThatThrownBy(() -> client().search("checkout-api",
                "sum(count_over_time({service=\"checkout-api\"}[1m]))", START, END, 500))
                .isInstanceOfSatisfying(IntegrationCallException.class,
                        e -> assertThat(e.hint()).isEqualTo(LokiLogClient.LOGQL_HINT));
        LOKI.verify(0, getRequestedFor(urlPathEqualTo("/loki/api/v1/query_range")));
    }

    private static void stub(ResponseDefinitionBuilder response) {
        LOKI.stubFor(get(urlPathEqualTo("/loki/api/v1/query_range")).willReturn(response));
    }

    private static ResponseDefinitionBuilder json(int status, String body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
    }

    private static LokiLogClient client() {
        Endpoint unset = new Endpoint(null, null, null, null, Duration.ofSeconds(2), Duration.ofSeconds(10));
        Endpoint logs = new Endpoint(LOKI.baseUrl(), null, null, null, Duration.ofSeconds(2), Duration.ofSeconds(10));
        return new LokiLogClient(new RestClients(new IntegrationProperties(unset, logs, unset, unset)), PROPERTIES,
                "service_name");
    }
}
