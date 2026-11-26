package dev.investigator.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.investigator.config.IntegrationProperties;
import dev.investigator.config.PropertiesConfig;
import dev.investigator.integration.changes.ChangeClient;
import dev.investigator.integration.http.RestClients;
import dev.investigator.integration.logs.LogClient;
import dev.investigator.integration.metrics.MetricPoint;
import dev.investigator.integration.metrics.MetricSeries;
import dev.investigator.integration.metrics.MetricsClient;
import dev.investigator.integration.metrics.PrometheusMetricsClient;
import dev.investigator.integration.mock.MockChangeClient;
import dev.investigator.integration.mock.MockLogClient;
import dev.investigator.integration.mock.MockMetricsClient;
import dev.investigator.integration.mock.MockTraceClient;
import dev.investigator.integration.traces.TraceClient;
import dev.investigator.testsupport.InvestigatorSpringBootTest;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * With the {@code real} profile and a metrics endpoint, full component scanning yields the Prometheus
 * client as the only {@link MetricsClient}, while the unconfigured integrations keep their mocks
 * (Req 7.1, 7.2, 7.4, CP-61).
 */
@InvestigatorSpringBootTest
@ActiveProfiles("real")
class RealProfileWiringTest {

    private static final WireMockServer PROMETHEUS = new WireMockServer(options().dynamicPort());

    static {
        PROMETHEUS.start();
    }

    @DynamicPropertySource
    static void endpoints(DynamicPropertyRegistry registry) {
        registry.add("investigator.integrations.metrics.base-url", PROMETHEUS::baseUrl);
        registry.add("investigator.integrations.logs.base-url", () -> "");
        registry.add("investigator.integrations.traces.base-url", () -> "");
        registry.add("investigator.integrations.changes.base-url", () -> "");
    }

    @AfterAll
    static void stop() {
        PROMETHEUS.stop();
    }

    @Autowired
    ApplicationContext context;

    @Test
    void realMetricsClientReplacesOnlyTheMetricsMock() {
        assertThat(context.getBeansOfType(MetricsClient.class).values())
                .singleElement().isInstanceOf(PrometheusMetricsClient.class);
        assertThat(context.getBeansOfType(LogClient.class).values()).singleElement().isInstanceOf(MockLogClient.class);
        assertThat(context.getBeansOfType(TraceClient.class).values())
                .singleElement().isInstanceOf(MockTraceClient.class);
        assertThat(context.getBeansOfType(ChangeClient.class).values())
                .singleElement().isInstanceOf(MockChangeClient.class);
    }

    @Test
    void queryRangeCallsPrometheusAndMapsTheMatrix() {
        String promql = "sum(rate(http_server_requests_seconds_count{service=\"checkout-api\",status=~\"5..\"}[1m]))";
        PROMETHEUS.stubFor(get(urlPathEqualTo("/api/v1/query_range"))
                .withQueryParam("query", equalTo(promql))
                .withQueryParam("start", equalTo("1795444200"))
                .withQueryParam("end", equalTo("1795444800"))
                .withQueryParam("step", equalTo("15"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        {"status":"success","data":{"resultType":"matrix","result":[
                          {"metric":{},"values":[[1795444215.5,"0.12"],[1795444200,"0.002"]]}]}}
                        """)));

        MetricSeries series = context.getBean(MetricsClient.class).queryRange("checkout-api", promql,
                Instant.parse("2026-11-23T14:30:00Z"), Instant.parse("2026-11-23T14:40:00Z"), Duration.ofSeconds(15));

        assertThat(series.step()).isEqualTo(Duration.ofSeconds(15));
        assertThat(series.points()).containsExactly(
                new MetricPoint(Instant.parse("2026-11-23T14:30:00Z"), 0.002),
                new MetricPoint(Instant.parse("2026-11-23T14:30:15.500Z"), 0.12));
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class, RestClients.class, PrometheusMetricsClient.class,
                    IntegrationConfig.class);

    @Test
    void blankEndpointFallsBackToMock() {
        runner.withPropertyValues("spring.profiles.active=real", "investigator.integrations.metrics.base-url= ")
                .run(ctx -> assertThat(ctx.getBeansOfType(MetricsClient.class).values())
                        .singleElement().isInstanceOf(MockMetricsClient.class));
    }

    @Test
    void invalidEndpointFailsStartupNamingTheProperty() {
        runner.withPropertyValues("spring.profiles.active=real",
                "investigator.integrations.metrics.base-url=prometheus:9090")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .rootCause().hasMessageContaining("investigator.integrations.metrics.base-url"));
    }

    @Test
    void credentialsAreMaskedInToString() {
        IntegrationProperties.Endpoint endpoint = new IntegrationProperties.Endpoint("http://prometheus:9090",
                "secret-token", null, null, Duration.ofSeconds(2), Duration.ofSeconds(10));
        assertThat(endpoint.toString()).doesNotContain("secret-token").contains("****");
    }
}
