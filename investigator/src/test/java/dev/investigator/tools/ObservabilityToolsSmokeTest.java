package dev.investigator.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.investigator.domain.Evidence;
import dev.investigator.domain.EvidenceType;
import dev.investigator.investigation.InvestigationContext;
import dev.investigator.investigation.InvestigationContextRegistry;
import dev.investigator.store.EvidenceRepository;
import dev.investigator.testsupport.InvestigatorSpringBootTest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Demo-scenario examples for each tool, the metric step formula, and the error paths. */
@InvestigatorSpringBootTest
class ObservabilityToolsSmokeTest {

    private static final Instant A = Instant.parse("2026-11-23T14:40:00Z");
    private static final String START = "2026-11-23T13:40:00Z";
    private static final String END = "2026-11-23T14:50:00Z";
    private static final String ERROR_RATIO_QUERY =
            "sum(rate(http_requests_total{service=\"checkout-api\",status=~\"5..\"}[5m]))"
                    + " / sum(rate(http_requests_total{service=\"checkout-api\"}[5m]))";

    @Autowired
    ObservabilityTools tools;

    @Autowired
    InvestigationContextRegistry registry;

    @Autowired
    EvidenceRepository repository;

    @Autowired
    ObjectMapper mapper;

    String incidentId;

    @BeforeEach
    void registerContext() {
        byte[] bytes = new byte[6];
        ThreadLocalRandom.current().nextBytes(bytes);
        incidentId = "inc-" + HexFormat.of().formatHex(bytes);
        registry.register(new InvestigationContext(incidentId, A.minus(Duration.ofMinutes(60)),
                A.plus(Duration.ofMinutes(10)), A.plus(Duration.ofMinutes(3)), "0".repeat(32),
                "ev-" + incidentId + "-1", A, 15));
    }

    @Test
    void metricStepKeepsAtMost300Points() {
        Duration min = Duration.ofSeconds(15);
        assertThat(ObservabilityTools.metricStep(Duration.ofMinutes(70), min, 300)).isEqualTo(Duration.ofSeconds(15));
        assertThat(ObservabilityTools.metricStep(Duration.ofSeconds(1), min, 300)).isEqualTo(Duration.ofSeconds(15));
        assertThat(ObservabilityTools.metricStep(Duration.ofHours(6), min, 300)).isEqualTo(Duration.ofSeconds(73));
        assertThat(ObservabilityTools.metricStep(Duration.ofSeconds(299 * 20L).plusMillis(1), min, 300))
                .isEqualTo(Duration.ofSeconds(21));
        for (long seconds : new long[] {1, 4485, 4486, 8970, 21_600, 86_400, 299 * 73L + 1}) {
            Duration span = Duration.ofSeconds(seconds);
            Duration step = ObservabilityTools.metricStep(span, min, 300);
            assertThat(span.dividedBy(step) + 1).as("points for %s", span).isLessThanOrEqualTo(300);
        }
    }

    @Test
    void getMetricStoresMetricEvidenceWithChangePoint() throws Exception {
        JsonNode json = mapper.readTree(tools.getMetric(incidentId, "checkout-api", ERROR_RATIO_QUERY, START, END));

        assertThat(json.get("evidenceId").asText()).isEqualTo("ev-" + incidentId + "-1");
        assertThat(json.get("step").asText()).isEqualTo("PT15S");
        assertThat(json.get("changePointAt").asText()).isEqualTo("2026-11-23T14:38:00Z");
        assertThat(json.get("clamped").asBoolean()).isFalse();
        Evidence ev = only();
        assertThat(ev.type()).isEqualTo(EvidenceType.METRIC);
        assertThat(ev.observedAt()).isEqualTo(Instant.parse("2026-11-23T14:38:00Z"));
        assertThat(ev.citation()).contains(ERROR_RATIO_QUERY);
    }

    @Test
    void searchLogsReturnsMatchCountAndTopMessage() throws Exception {
        JsonNode json = mapper.readTree(tools.searchLogs(incidentId, "checkout-api",
                "{service=\"checkout-api\"} |= \"PaymentGatewayTimeoutException\"", START, END));

        assertThat(json.get("matchCount").asInt()).isEqualTo(214);
        assertThat(json.get("topMessages").get(0).get("message").asText()).startsWith("PaymentGatewayTimeoutException");
        assertThat(json.get("firstSeen").asText()).isEqualTo("2026-11-23T14:38:00Z");
        assertThat(only().type()).isEqualTo(EvidenceType.LOG);
    }

    @Test
    void findProblemTracesReportsFailingProviderSpan() throws Exception {
        JsonNode json = mapper.readTree(tools.findProblemTraces(incidentId, "checkout-api", START, END));

        assertThat(json.get("errorTraceCount").asInt()).isPositive();
        assertThat(json.get("topFailingSpans").findValuesAsText("peerService")).contains("payment-provider");
        assertThat(json.get("exampleTraceIds").size()).isEqualTo(3);
        assertThat(only().type()).isEqualTo(EvidenceType.TRACE);
    }

    @Test
    void getRecentChangesStoresOneDeploymentEvidencePerChange() throws Exception {
        JsonNode json = mapper.readTree(tools.getRecentChanges(incidentId, "checkout-api", 24));

        assertThat(json.get("changes").size()).isEqualTo(1);
        JsonNode change = json.get("changes").get(0);
        assertThat(change.get("version").asText()).isEqualTo("v2.14.3");
        assertThat(change.get("changedFiles").size()).isEqualTo(2);
        Evidence ev = only();
        assertThat(change.get("evidenceId").asText()).isEqualTo(ev.id());
        assertThat(ev.type()).isEqualTo(EvidenceType.DEPLOYMENT);
        assertThat(ev.observedAt()).isEqualTo(A.minus(Duration.ofMinutes(14)));
        assertThat(ev.citation()).isEqualTo("https://github.com/example/checkout-api/pull/1423");
        assertThat(ev.headline()).isEqualTo("checkout-api v2.14.3 deployed by dev-alice");
    }

    @Test
    void findRunbookWithoutCatalogEntryReturnsNotFound() throws Exception {
        JsonNode json = mapper.readTree(tools.findRunbook(incidentId, "payment-gateway", "timeouts"));

        assertThat(json.get("found").asBoolean()).isFalse();
        assertThat(repository.findByIncident(incidentId)).isEmpty();
    }

    @Test
    void unparseableTimeGivesErrorResultAndNoEvidence() throws Exception {
        JsonNode json = mapper.readTree(tools.getMetric(incidentId, "checkout-api", ERROR_RATIO_QUERY,
                "yesterday", END));

        assertThat(json.get("error").asText()).isEqualTo("start is not an ISO-8601 instant");
        assertThat(json.get("hint").asText()).contains("ISO-8601");
        assertThat(mapper.readTree(tools.searchLogs(incidentId, "checkout-api", "x", END, START))
                .get("error").asText()).isEqualTo("start must be before end");
        assertThat(repository.findByIncident(incidentId)).isEmpty();
    }

    @Test
    void offsetDateTimeIsAccepted() throws Exception {
        JsonNode json = mapper.readTree(tools.findProblemTraces(incidentId, "checkout-api",
                "2026-11-23T19:10:00+05:30", "2026-11-23T20:20:00+05:30"));

        assertThat(json.get("appliedStart").asText()).isEqualTo(START);
    }

    @Test
    void clientExceptionGivesErrorResultAndNoEvidence() throws Exception {
        // The mock clients reject a null service with an exception.
        JsonNode json = mapper.readTree(tools.searchLogs(incidentId, null, "x", START, END));

        assertThat(json.get("error").asText()).isEqualTo(ObservabilityTools.INTEGRATION_FAILED);
        assertThat(json.get("hint").asText()).isEqualTo(ObservabilityTools.INTEGRATION_HINT);
        assertThat(repository.findByIncident(incidentId)).isEmpty();
    }

    @Test
    void unknownInvestigationIsRejected() throws Exception {
        JsonNode json = mapper.readTree(tools.getRecentChanges("inc-000000000000", "checkout-api", 24));

        assertThat(json.get("error").asText()).isEqualTo(ObservabilityTools.NOT_RUNNING);
    }

    private Evidence only() {
        List<Evidence> stored = repository.findByIncident(incidentId);
        assertThat(stored).hasSize(1);
        return stored.get(0);
    }
}
