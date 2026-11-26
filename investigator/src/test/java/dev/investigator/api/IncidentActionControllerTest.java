package dev.investigator.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.investigator.domain.Incident;
import dev.investigator.domain.IncidentStatus;
import dev.investigator.domain.IncidentTimeline;
import dev.investigator.domain.Investigation;
import dev.investigator.domain.InvestigationReport;
import dev.investigator.domain.InvestigationStatus;
import dev.investigator.domain.Severity;
import dev.investigator.store.IncidentRepository;
import dev.investigator.store.InvestigationRepository;
import dev.investigator.store.ReportRepository;
import dev.investigator.testsupport.InvestigatorSpringBootTest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Example tests for {@code POST /api/v1/incidents/{id}/reinvestigate} (Req 9.1 to 9.4). */
@InvestigatorSpringBootTest
@AutoConfigureMockMvc
class IncidentActionControllerTest {

    private static final Instant T0 = Instant.parse("2026-11-23T14:40:00Z");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private IncidentRepository incidents;
    @Autowired
    private InvestigationRepository investigations;
    @Autowired
    private ReportRepository reports;

    @Test
    void unknownIncidentGives404() throws Exception {
        reinvestigate(randomIncidentId()).andExpect(status().isNotFound());
        reinvestigate("not-an-id").andExpect(status().isNotFound());
    }

    @Test
    void activeInvestigationGives409AndIsLeftAlone() throws Exception {
        String id = storedIncident();
        investigations.create(pending(id));

        reinvestigate(id).andExpect(status().isConflict());
        assertThat(investigations.find(id)).map(Investigation::status).contains(InvestigationStatus.PENDING);
        investigations.transition(id, InvestigationStatus.PENDING, InvestigationStatus.FAILED);
    }

    @Test
    void endedInvestigationIsRerunAndItsReportBecomesTheNextVersion() throws Exception {
        String id = storedIncident();
        investigations.create(pending(id));
        investigations.transition(id, InvestigationStatus.PENDING, InvestigationStatus.RUNNING);
        investigations.transition(id, InvestigationStatus.RUNNING, InvestigationStatus.COMPLETED);
        InvestigationReport first = new InvestigationReport(id, InvestigationStatus.COMPLETED, "first report", null,
                null, null, null, null, T0.plusSeconds(60));
        reports.saveIfAbsent(first);

        reinvestigate(id).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.incidentId").value(id))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.reportUrl").value("/api/v1/incidents/" + id + "/report"));
        // The rerun is in progress until its report is stored and its run ends.
        reinvestigate(id).andExpect(status().isConflict());

        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline && reports.find(id, 2).isEmpty()) {
            Thread.sleep(10);
        }
        assertThat(reports.find(id, 2)).isPresent();
        assertThat(investigations.find(id)).map(Investigation::status).get()
                .matches(s -> s != InvestigationStatus.PENDING && s != InvestigationStatus.RUNNING);
        mvc.perform(get("/api/v1/incidents/" + id + "/report")).andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value(reports.find(id, 2).orElseThrow().summary()));
        mvc.perform(get("/api/v1/incidents/" + id + "/reports/1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value("first report"));
        mvc.perform(get("/api/v1/incidents/" + id + "/reports/3")).andExpect(status().isNotFound());
    }

    private ResultActions reinvestigate(String id) throws Exception {
        return mvc.perform(post("/api/v1/incidents/" + id + "/reinvestigate"));
    }

    private String storedIncident() {
        String id = randomIncidentId();
        incidents.save(new Incident(id, "HighErrorRate on checkout-api", Severity.CRITICAL, "checkout-api", T0,
                "HighErrorRate", null, null, id.substring(4) + "0".repeat(52), IncidentStatus.REPORTED));
        return id;
    }

    private static Investigation pending(String id) {
        return new Investigation(id, InvestigationStatus.PENDING, T0.minusSeconds(3600), T0.plusSeconds(600), 0,
                List.of(), List.of(), new IncidentTimeline(List.of()), List.of());
    }

    private static String randomIncidentId() {
        byte[] bytes = new byte[6];
        ThreadLocalRandom.current().nextBytes(bytes);
        return "inc-" + HexFormat.of().formatHex(bytes);
    }
}
