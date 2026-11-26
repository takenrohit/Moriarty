package dev.investigator.api;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.investigator.domain.InvestigationReport;
import dev.investigator.domain.InvestigationStatus;
import dev.investigator.store.ReportRepository;
import dev.investigator.testsupport.InvestigatorSpringBootTest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Example tests for {@code POST /api/v1/reports/{incidentId}/feedback} (Req 8.1 to 8.4). */
@InvestigatorSpringBootTest
@AutoConfigureMockMvc
class FeedbackControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ReportRepository reports;

    @Test
    void feedbackIsStoredAndRepeatsAreAllowed() throws Exception {
        String id = storedReport();
        submit(id, "{\"rating\":\"helpful\",\"note\":\"pointed at the deploy\",\"submittedBy\":\"rohit\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.rating").value("helpful"))
                .andExpect(jsonPath("$.submittedBy").value("rohit"));
        submit(id, "{\"rating\":\"UNHELPFUL\"}").andExpect(status().isCreated());

        mvc.perform(get("/api/v1/reports/" + id + "/feedback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].note").value("pointed at the deploy"))
                .andExpect(jsonPath("$[1].rating").value("unhelpful"));
    }

    @Test
    void unknownReportGives404() throws Exception {
        submit(randomIncidentId(), "{\"rating\":\"helpful\"}").andExpect(status().isNotFound());
        submit("not-an-id", "{\"rating\":\"helpful\"}").andExpect(status().isNotFound());
    }

    @Test
    void invalidSubmissionGives400AndStoresNothing() throws Exception {
        String id = storedReport();
        submit(id, "{\"rating\":\"5\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("rating"));
        submit(id, "{}").andExpect(status().isBadRequest());
        submit(id, "{\"rating\":\"helpful\",\"note\":\"" + "x".repeat(2001) + "\"}")
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/v1/reports/" + id + "/feedback")).andExpect(jsonPath("$", hasSize(0)));
    }

    private ResultActions submit(String incidentId, String body) throws Exception {
        return mvc.perform(post("/api/v1/reports/" + incidentId + "/feedback")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String storedReport() {
        String id = randomIncidentId();
        reports.saveIfAbsent(new InvestigationReport(id, InvestigationStatus.COMPLETED, "summary", null, null, null,
                null, null, Instant.parse("2026-11-23T14:45:00Z")));
        return id;
    }

    private static String randomIncidentId() {
        byte[] bytes = new byte[6];
        ThreadLocalRandom.current().nextBytes(bytes);
        return "inc-" + HexFormat.of().formatHex(bytes);
    }
}
