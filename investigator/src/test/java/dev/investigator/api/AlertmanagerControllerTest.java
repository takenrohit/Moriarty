package dev.investigator.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.investigator.alert.Fingerprints;
import dev.investigator.domain.Incident;
import dev.investigator.domain.InvestigationStatus;
import dev.investigator.domain.Severity;
import dev.investigator.store.IncidentRepository;
import dev.investigator.store.InvestigationRepository;
import dev.investigator.testsupport.InvestigatorSpringBootTest;
import dev.investigator.testsupport.ScriptedChatModel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Example tests for {@code POST /api/v1/alerts/alertmanager} (Req 8.1 to 8.5). */
@InvestigatorSpringBootTest
@AutoConfigureMockMvc
class AlertmanagerControllerTest {

    /** An Alertmanager v4 payload as sent for the HighErrorRate rule: two firing alerts and one resolved. */
    private static final String PAYLOAD = """
            {
              "version": "4",
              "groupKey": "{}:{alertname=\\"HighErrorRate\\"}",
              "status": "firing",
              "receiver": "investigator",
              "commonLabels": { "alertname": "HighErrorRate" },
              "alerts": [
                {
                  "status": "firing",
                  "labels": { "alertname": "HighErrorRate", "service": "checkout-api", "severity": "critical" },
                  "annotations": { "summary": "5xx above 5%", "dashboard_url": "http://localhost:3000/explore" },
                  "startsAt": "2026-11-23T14:40:00.123Z",
                  "endsAt": "0001-01-01T00:00:00Z",
                  "generatorURL": "http://localhost:9090/graph?g0.expr=x",
                  "fingerprint": "abc123"
                },
                {
                  "status": "firing",
                  "labels": { "alertname": "HighLatency", "service": "payment-gateway" },
                  "startsAt": "2026-11-23T20:12:00+05:30"
                },
                {
                  "status": "resolved",
                  "labels": { "alertname": "HighErrorRate", "service": "payment-provider" },
                  "startsAt": "2026-11-23T13:00:00Z",
                  "endsAt": "2026-11-23T13:30:00Z"
                }
              ]
            }
            """;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IncidentRepository incidents;

    @Autowired
    private InvestigationRepository investigations;

    @Autowired
    private ScriptedChatModel model;

    @BeforeEach
    void resetModel() {
        model.reset();
    }

    /** Waits for the investigations started by accepted alerts, as in {@code AlertControllerTest}. */
    @AfterEach
    void awaitStartedInvestigations() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline && incidents.findAllNewestFirst().stream()
                .map(i -> investigations.find(i.id()))
                .flatMap(Optional::stream)
                .anyMatch(i -> i.status() == InvestigationStatus.PENDING || i.status() == InvestigationStatus.RUNNING)) {
            Thread.sleep(10);
        }
    }

    @Test
    void eachFiringAlertBecomesAnIncidentAndResolvedAlertsAreSkipped() throws Exception {
        String response = send(PAYLOAD)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.incidentIds", hasSize(2)))
                .andReturn().getResponse().getContentAsString();
        JsonNode ids = objectMapper.readTree(response).get("incidentIds");

        Incident first = incidents.find(ids.get(0).asText()).orElseThrow();
        Map<String, String> labels = Map.of("alertname", "HighErrorRate", "service", "checkout-api",
                "severity", "critical");
        assertThat(first.title()).isEqualTo("HighErrorRate on checkout-api");
        assertThat(first.alertName()).isEqualTo("HighErrorRate");
        assertThat(first.affectedService()).isEqualTo("checkout-api");
        assertThat(first.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(first.detectedAt()).isEqualTo(Instant.parse("2026-11-23T14:40:00.123Z"));
        assertThat(first.labels()).isEqualTo(labels);
        assertThat(first.dashboardLinks())
                .containsExactly("http://localhost:9090/graph?g0.expr=x", "http://localhost:3000/explore");
        assertThat(first.fingerprint()).isEqualTo(Fingerprints.of("HighErrorRate", "checkout-api", labels));

        Incident second = incidents.find(ids.get(1).asText()).orElseThrow();
        assertThat(second.affectedService()).isEqualTo("payment-gateway");
        assertThat(second.severity()).isEqualTo(Severity.WARNING);
        assertThat(second.detectedAt()).isEqualTo(Instant.parse("2026-11-23T14:42:00Z"));
    }

    @Test
    void resolvedOnlyPayloadIsAcknowledgedWithoutIncidents() throws Exception {
        int before = incidents.findAllNewestFirst().size();
        send("""
                { "version": "4", "status": "resolved", "alerts": [
                  { "status": "resolved", "labels": { "alertname": "HighErrorRate", "service": "checkout-api" },
                    "startsAt": "2026-11-23T14:40:00Z" } ] }
                """)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.incidentIds", empty()));
        assertThat(incidents.findAllNewestFirst()).hasSize(before);
    }

    @Test
    void missingServiceLabelIsRejectedNamingTheLabelAndNothingIsStored() throws Exception {
        int before = incidents.findAllNewestFirst().size();
        send("""
                { "version": "4", "status": "firing", "alerts": [
                  { "status": "firing", "labels": { "alertname": "HighErrorRate", "service": "checkout-api" },
                    "startsAt": "2026-11-23T14:40:00Z" },
                  { "status": "firing", "labels": { "alertname": "HighErrorRate", "job": "secret-marker-7f3a" },
                    "startsAt": "not-a-time" } ] }
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", contains("alerts[1].labels.service", "alerts[1].startsAt")));
        assertThat(incidents.findAllNewestFirst()).hasSize(before);
    }

    @Test
    void bodyOver64KbIsRejectedWith413() throws Exception {
        String padding = "x".repeat(RequestSizeLimitFilter.MAX_BODY_BYTES);
        send("{ \"version\": \"4\", \"extra\": \"" + padding + "\", \"alerts\": [] }")
                .andExpect(status().isPayloadTooLarge());
    }

    private ResultActions send(String body) throws Exception {
        return mvc.perform(post("/api/v1/alerts/alertmanager")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.getBytes(StandardCharsets.UTF_8)));
    }
}
