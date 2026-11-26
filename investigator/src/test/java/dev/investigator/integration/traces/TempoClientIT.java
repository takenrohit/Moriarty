package dev.investigator.integration.traces;

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
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * {@link TempoTraceClient} against a WireMock Tempo serving recorded-style {@code /api/search} bodies: request
 * shape (TraceQL for error or slow spans of the service, range in seconds, {@code limit + 1}, spans per span
 * set), traces mapped with their matched spans, no matches, and HTTP errors (Req 5.1, 5.4, 5.5, 14.4). No
 * credentials; WireMock runs in-process. Tagged {@code containers} like every {@code *IT} (CP-55).
 */
@Tag("containers")
class TempoClientIT {

    private static final WireMockServer TEMPO = new WireMockServer(options().dynamicPort());

    private static final Instant START = Instant.parse("2026-11-23T14:30:00Z");
    private static final Instant END = Instant.parse("2026-11-23T14:40:00Z");
    private static final String SENT_QUERY = "{ resource.service.name = \"checkout-api\" && (status = error"
            + " || duration >= 2000ms) } | select(span.peer.service, span.server.address, status)";

    /**
     * Shaped like Tempo 2.x search output: newest trace first, matched spans per span set with the selected
     * attributes, {@code durationMs} omitted for a sub-millisecond trace.
     */
    private static final String SEARCH_BODY = """
            {"traces":[
              {"traceID":"7a3f9c2e4b1d8f06a5c3e9b7d2f4a1c8","rootServiceName":"checkout-api",
               "rootTraceName":"POST /checkout","startTimeUnixNano":"1795444140500000000","durationMs":5012,
               "spanSets":[{"spans":[
                 {"spanID":"a1b2c3d4e5f60718","name":"POST","startTimeUnixNano":"1795444140510000000",
                  "durationNanos":"5000123456","attributes":[
                    {"key":"service.name","value":{"stringValue":"checkout-api"}},
                    {"key":"peer.service","value":{"stringValue":"payment-gateway"}},
                    {"key":"status","value":{"stringValue":"error"}}]},
                 {"spanID":"0f1e2d3c4b5a6978","name":"POST /checkout","startTimeUnixNano":"1795444140500000000",
                  "durationNanos":"5012000000","attributes":[
                    {"key":"service.name","value":{"stringValue":"checkout-api"}},
                    {"key":"status","value":{"stringValue":"error"}}]}],
                 "matched":2}]},
              {"traceID":"1c8e5b2a9d4f7036","rootServiceName":"checkout-api","rootTraceName":"POST /checkout",
               "startTimeUnixNano":"1795444080000000000","durationMs":2300,
               "spanSets":[{"spans":[
                 {"spanID":"99aa88bb77cc66dd","name":"POST /checkout","startTimeUnixNano":"1795444080000000000",
                  "durationNanos":"2300000000","attributes":[
                    {"key":"server.address","value":{"stringValue":"mock-payment-gateway"}}]}],
                 "matched":1}]}],
             "metrics":{"inspectedBytes":"40960","completedJobs":1,"totalJobs":1}}
            """;

    private static final InvestigatorProperties PROPERTIES = new Binder(new MapConfigurationPropertySource())
            .bindOrCreate("investigator", InvestigatorProperties.class);

    @BeforeAll
    static void start() {
        TEMPO.start();
    }

    @AfterAll
    static void stop() {
        TEMPO.stop();
    }

    @BeforeEach
    void reset() {
        TEMPO.resetAll();
    }

    @Test
    void searchSendsTraceqlRangeAndLimitAndMapsTracesInStartOrder() {
        stub(json(200, SEARCH_BODY));

        TraceSearchResult result = client().findProblemTraces("checkout-api", START, END, Duration.ofSeconds(2), 20);

        TEMPO.verify(1, getRequestedFor(urlPathEqualTo("/api/search"))
                .withQueryParam("q", equalTo(SENT_QUERY))
                .withQueryParam("start", equalTo("1795444200"))
                .withQueryParam("end", equalTo("1795444800"))
                .withQueryParam("limit", equalTo("21"))
                .withQueryParam("spss", equalTo("20"))
                .withHeader("Authorization", absent()));
        assertThat(result.truncated()).isFalse();
        assertThat(result.traces()).containsExactly(
                new TraceRecord("1c8e5b2a9d4f7036", Instant.parse("2026-11-23T14:28:00Z"), Duration.ofMillis(2300),
                        false, List.of(new SpanRecord("checkout-api", "POST /checkout",
                                "mock-payment-gateway", Duration.ofMillis(2300), false))),
                new TraceRecord("7a3f9c2e4b1d8f06a5c3e9b7d2f4a1c8", Instant.parse("2026-11-23T14:29:00.500Z"),
                        Duration.ofMillis(5012), true, List.of(
                                new SpanRecord("checkout-api", "POST", "payment-gateway",
                                        Duration.ofNanos(5_000_123_456L), true),
                                new SpanRecord("checkout-api", "POST /checkout", null,
                                        Duration.ofMillis(5012), true))));
    }

    @Test
    void moreTracesThanTheLimitIsTruncatedToTheEarliest() {
        stub(json(200, SEARCH_BODY));

        TraceSearchResult result = client().findProblemTraces("checkout-api", START, END, Duration.ofSeconds(2), 1);

        TEMPO.verify(1, getRequestedFor(urlPathEqualTo("/api/search")).withQueryParam("limit", equalTo("2")));
        assertThat(result.truncated()).isTrue();
        assertThat(result.traces()).extracting(TraceRecord::traceId).containsExactly("1c8e5b2a9d4f7036");
    }

    @Test
    void noMatchingTracesIsEmptyAndNotTruncated() {
        stub(json(200, """
                {"traces":[],"metrics":{"inspectedBytes":"0","completedJobs":1,"totalJobs":1}}
                """));

        TraceSearchResult result = client().findProblemTraces("checkout-api", START, END, Duration.ofSeconds(2), 50);

        assertThat(result.traces()).isEmpty();
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void httpErrorThrows() {
        stub(aResponse().withStatus(400).withHeader("Content-Type", "text/plain")
                .withBody("invalid TraceQL query: parse error at line 1, col 3"));

        assertThatThrownBy(() -> client().findProblemTraces("checkout-api", START, END, Duration.ofSeconds(2), 50))
                .isInstanceOfSatisfying(IntegrationCallException.class,
                        e -> assertThat(e.integration()).isEqualTo("traces"))
                .hasMessageContaining("HTTP 400")
                .hasMessageContaining("invalid TraceQL");
    }

    private static void stub(ResponseDefinitionBuilder response) {
        TEMPO.stubFor(get(urlPathEqualTo("/api/search")).willReturn(response));
    }

    private static ResponseDefinitionBuilder json(int status, String body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
    }

    private static TempoTraceClient client() {
        Endpoint unset = new Endpoint(null, null, null, null, Duration.ofSeconds(2), Duration.ofSeconds(10));
        Endpoint traces = new Endpoint(TEMPO.baseUrl(), null, null, null, Duration.ofSeconds(2),
                Duration.ofSeconds(10));
        return new TempoTraceClient(new RestClients(new IntegrationProperties(unset, unset, traces, unset)),
                PROPERTIES);
    }
}
