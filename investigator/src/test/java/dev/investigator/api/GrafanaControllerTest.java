package dev.investigator.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Example tests for {@code POST /api/v1/alerts/grafana} (Req 9.1 to 9.5). */
@InvestigatorSpringBootTest
@AutoConfigureMockMvc
class GrafanaControllerTest {

    /** A Grafana unified-alerting webhook payload with one firing and one resolved alert. */
    private static final String PAYLOAD = """
            {
              "receiver": "investigator",
              "status": "firing",
              "orgId": 1,
              "title": "[FIRING:1] HighErrorRate",
              "alerts": [
                {
                  "status": "firing",
                  "labels": { "alertname": "HighErrorRate", "grafana_folder": "checkout",
                              "service": "checkout-api", "severity": "High" },
                  "annotations": { "summary": "5xx above 5%", "__dashboardUid__": "abc" },
                  "startsAt": "2026-11-23T14:40:00Z",
                  "endsAt": "0001-01-01T00:00:00Z",
                  "generatorURL": "http://localhost:3000/alerting/grafana/rule-1/view",
                  "dashboardURL": "http://localhost:3000/d/abc",
                  "panelURL": "http://localhost:3000/d/abc?viewPanel=2",
                  "valueString": "[ var='A' value=0.12 ]"
                },
                {
                  "status": "resolved",
                  "labels": { "alertname": "HighLatency", "service": "payment-gateway" },
                  "startsAt": "2026-11-23T13:00:00Z",
                  "dashboardURL": "",
                  "panelURL": ""
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
    void firingAlertBecomesAnIncidentWithRuleNameLabelsAndGrafanaLinks() throws Exception {
        String response = send(PAYLOAD)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.incidentIds", hasSize(1)))
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(response).get("incidentIds").get(0).asText();

        Incident incident = incidents.find(id).orElseThrow();
        assertThat(incident.alertName()).isEqualTo("HighErrorRate");
        assertThat(incident.affectedService()).isEqualTo("checkout-api");
        assertThat(incident.severity()).isEqualTo(Severity.HIGH);
        assertThat(incident.detectedAt()).isEqualTo(Instant.parse("2026-11-23T14:40:00Z"));
        assertThat(incident.labels()).containsEntry("grafana_folder", "checkout").hasSize(4);
        assertThat(incident.dashboardLinks()).containsExactly(
                "http://localhost:3000/d/abc",
                "http://localhost:3000/d/abc?viewPanel=2",
                "http://localhost:3000/alerting/grafana/rule-1/view");
    }

    @Test
    void resolvedAndOkAlertsAreAcknowledgedWithoutIncidents() throws Exception {
        int before = incidents.findAllNewestFirst().size();
        send("""
                { "status": "resolved", "alerts": [
                  { "status": "resolved", "labels": { "alertname": "A", "service": "checkout-api" } },
                  { "status": "OK", "labels": { "alertname": "B", "service": "checkout-api" } } ] }
                """)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.incidentIds", empty()));
        assertThat(incidents.findAllNewestFirst()).hasSize(before);
    }

    @Test
    void unmappablePayloadIsRejectedNamingTheMissingFields() throws Exception {
        int before = incidents.findAllNewestFirst().size();
        send("""
                { "status": "firing", "alerts": [
                  { "status": "firing", "labels": { "grafana_folder": "checkout" },
                    "startsAt": "2026-11-23T14:40:00Z" },
                  { "status": "pending", "labels": { "alertname": "A", "service": "checkout-api" } } ] }
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", contains(
                        "alerts[0].labels.alertname", "alerts[0].labels.service", "alerts[1].status")));
        send("{ \"status\": \"firing\" }")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", contains("alerts")));
        assertThat(incidents.findAllNewestFirst()).hasSize(before);
    }

    private ResultActions send(String body) throws Exception {
        return mvc.perform(post("/api/v1/alerts/grafana")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.getBytes(StandardCharsets.UTF_8)));
    }
}
