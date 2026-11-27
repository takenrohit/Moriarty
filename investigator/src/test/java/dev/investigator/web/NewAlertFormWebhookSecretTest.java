package dev.investigator.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.investigator.domain.Incident;
import dev.investigator.store.IncidentRepository;
import dev.investigator.testsupport.InvestigatorSpringBootTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * With a Webhook_Secret configured the New_Alert_Form is disabled: the page shows a notice instead of the
 * fields, {@code POST /incidents} answers 403 and creates nothing, and the secret never reaches the page
 * (web UI Req 3.8, design decision 7). The webhook filter guards only {@code /api/v1/alerts}, so the 403
 * comes from the page controller, not a 401 challenge.
 */
@InvestigatorSpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "investigator.webhook.secret=" + NewAlertFormWebhookSecretTest.SECRET)
class NewAlertFormWebhookSecretTest {

    static final String SECRET = "test-only-secret";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private IncidentRepository incidents;

    @Test
    void formPageShowsTheDisabledNoticeAndNoInputs() throws Exception {
        String page = html(mvc.perform(get("/incidents/new").param("scenario", "bad-deploy"))
                .andExpect(status().isOk()));

        assertThat(page)
                .contains("id=\"form-disabled\"", IncidentPageController.FORM_DISABLED_MESSAGE)
                .doesNotContain("<form", "<input", "<select", "<textarea", "<button", "name=\"alert\"")
                .doesNotContain(SECRET);
    }

    @Test
    void validSubmissionIsForbiddenAndCreatesNoIncident() throws Exception {
        String alert = "SecretGuardedAlert" + System.nanoTime();
        int before = incidents.findAllNewestFirst().size();

        String page = html(mvc.perform(post("/incidents")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("scenario", "payment-gateway")
                        .param("alert", alert)
                        .param("service", "checkout-api")
                        .param("severity", "critical")
                        .param("detectedAt", "2026-11-23T20:10:00+05:30"))
                .andExpect(status().isForbidden()));

        assertThat(page)
                .contains(IncidentPageController.FORM_DISABLED_MESSAGE)
                .doesNotContain("<input", SECRET);
        assertThat(incidents.findAllNewestFirst()).hasSize(before)
                .extracting(Incident::alertName).doesNotContain(alert);
    }

    private static String html(ResultActions result) throws Exception {
        return result.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
