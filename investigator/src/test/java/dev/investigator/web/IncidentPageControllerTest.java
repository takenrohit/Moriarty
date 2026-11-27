package dev.investigator.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.investigator.domain.ActionType;
import dev.investigator.domain.Confidence;
import dev.investigator.domain.EvidenceRef;
import dev.investigator.domain.EvidenceType;
import dev.investigator.domain.Hypothesis;
import dev.investigator.domain.Incident;
import dev.investigator.domain.IncidentStatus;
import dev.investigator.domain.IncidentTimeline;
import dev.investigator.domain.Investigation;
import dev.investigator.domain.InvestigationReport;
import dev.investigator.domain.InvestigationStatus;
import dev.investigator.domain.RecommendedAction;
import dev.investigator.domain.Severity;
import dev.investigator.domain.TimelineEntry;
import dev.investigator.report.ReportRenderer;
import dev.investigator.store.InMemoryIncidentRepository;
import dev.investigator.store.InMemoryInvestigationRepository;
import dev.investigator.store.InMemoryReportRepository;
import dev.investigator.store.IncidentRepository;
import dev.investigator.store.InvestigationRepository;
import dev.investigator.store.ReportRepository;
import dev.investigator.testsupport.InvestigatorSpringBootTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.ViewResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

/** MockMvc examples for the console pages served by {@link IncidentPageController}. */
@InvestigatorSpringBootTest
@AutoConfigureMockMvc
class IncidentPageControllerTest {

    private static final Instant T0 = Instant.parse("2026-11-23T14:40:00Z");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private IncidentRepository incidents;
    @Autowired
    private InvestigationRepository investigations;
    @Autowired
    private ReportRepository reports;
    @Autowired
    private ReportRenderer renderer;
    @Autowired
    private ThymeleafViewResolver viewResolver;

    @Test
    void listPageHasConsoleShellWithHeaderLinksZoneAndStylesheet() throws Exception {
        String page = html(mvc.perform(get("/incidents")).andExpect(status().isOk()));

        assertThat(page)
                .contains("href=\"/css/console.css\"", "src=\"/js/console.js\"")
                .contains("href=\"/incidents\"", "href=\"/incidents/new\"")
                .contains("Times in", renderer.times().zone().getId())
                .doesNotContain("href=\"http", "src=\"http", "href=\"//", "src=\"//")
                .doesNotContain("<style");
    }

    @Test
    void reportPageForStoredReportRendersStatusAndSeverityBadges() throws Exception {
        String id = storedIncident(Severity.HIGH);
        investigations.create(pending(id));
        investigations.transition(id, InvestigationStatus.PENDING, InvestigationStatus.RUNNING);
        investigations.transition(id, InvestigationStatus.RUNNING, InvestigationStatus.COMPLETED);
        String evidenceId = "ev-" + id + "-1";
        reports.saveIfAbsent(new InvestigationReport(id, InvestigationStatus.COMPLETED, "checkout errors rose",
                new IncidentTimeline(List.of(new TimelineEntry(T0, "alert fires", List.of(evidenceId)))),
                List.of(new Hypothesis("payment-provider is slow", Confidence.HIGH, List.of(evidenceId), List.of(),
                        false)),
                List.of(new RecommendedAction(1, ActionType.INVESTIGATE, "check the provider", false,
                        List.of(evidenceId))),
                List.of(), List.of(new EvidenceRef(evidenceId, EvidenceType.ALERT, "alert-intake", T0,
                        "HighErrorRate fired", "alert payload")),
                T0.plusSeconds(60)));

        String page = html(mvc.perform(get("/incidents/{id}", id)).andExpect(status().isOk()));

        assertThat(page)
                .contains("href=\"/css/console.css\"", "href=\"/incidents/new\"")
                .contains("class=\"badge badge-status-COMPLETED\">COMPLETED</span>")
                .contains("class=\"badge badge-severity-High\">High</span>")
                .contains("checkout errors rose", "payment-provider is slow", "[" + evidenceId + "]")
                .contains("Times in", renderer.times().zone().getId());
        assertThat(page).doesNotContain("href=\"http", "src=\"http", "href=\"//", "src=\"//", "th:utext");

        String list = html(mvc.perform(get("/incidents")).andExpect(status().isOk()));
        assertThat(list).contains("/incidents/" + id, "badge-status-COMPLETED", "badge-severity-High");
    }

    @Test
    void emptyStoreShowsEmptyStateWithNewAlertLink() throws Exception {
        // The shared context's stores hold other tests' incidents, so render with fresh, empty stores
        // through the application's own Thymeleaf view resolver.
        MockMvc empty = MockMvcBuilders.standaloneSetup(new IncidentPageController(new InMemoryIncidentRepository(),
                new InMemoryInvestigationRepository(), new InMemoryReportRepository(), renderer))
                // Delegate so the standalone builder does not re-bind the shared resolver to its stub context.
                .setViewResolvers((ViewResolver) viewResolver::resolveViewName)
                .build();

        String page = html(empty.perform(get("/incidents")).andExpect(status().isOk()));
        assertThat(page)
                .contains("<tbody id=\"incident-rows\">", "class=\"empty-state\"", "No incidents yet.")
                .contains("<a href=\"/incidents/new\">Raise a new alert</a>")
                .contains("id=\"refresh-failed\"", "data-live-list")
                .doesNotContain("class=\"incident-row\"");
        assertThat(page.substring(page.indexOf("id=\"refresh-failed\""))).startsWith(
                "id=\"refresh-failed\" class=\"refresh-failed\" role=\"status\" hidden>");

        String fragment = html(empty.perform(get("/incidents/fragment")).andExpect(status().isOk()));
        assertThat(fragment).contains("No incidents yet.", "<a href=\"/incidents/new\">Raise a new alert</a>");
    }

    @Test
    void listRowsAppearNewestFirstAndLinkToTheReportPage() throws Exception {
        String older = storedIncident(Severity.WARNING, T0.plus(Duration.ofDays(400)));
        String newer = storedIncident(Severity.CRITICAL, T0.plus(Duration.ofDays(401)));

        String page = html(mvc.perform(get("/incidents")).andExpect(status().isOk()));

        assertThat(page)
                .contains("<a href=\"/incidents/" + newer + "\">" + newer + "</a>")
                .contains("<a href=\"/incidents/" + older + "\">" + older + "</a>")
                .contains("badge-severity-Critical", "badge-severity-Warning", "badge-status-PENDING")
                .doesNotContain("class=\"empty-state\"");
        assertThat(page.indexOf("/incidents/" + newer)).isLessThan(page.indexOf("/incidents/" + older));
    }

    @Test
    void fragmentReturnsOnlyTheIncidentRowsTbody() throws Exception {
        String id = storedIncident(Severity.INFO, T0.plus(Duration.ofDays(500)));

        // Mapped to listFragment, not to /incidents/{id} (which would 404 on "fragment").
        String fragment = html(mvc.perform(get("/incidents/fragment")).andExpect(status().isOk())).strip();

        assertThat(fragment).startsWith("<tbody id=\"incident-rows\">").endsWith("</tbody>");
        assertThat(fragment)
                .contains("<a href=\"/incidents/" + id + "\">" + id + "</a>", "badge-severity-Info")
                .doesNotContain("<html", "<head", "<header", "<thead", "<table", "Times in", "console.css");
    }

    @Test
    void malformedIdGives404HtmlErrorPage() throws Exception {
        assertErrorPage("not-an-id");
        assertErrorPage("inc-XYZ");
    }

    @Test
    void unknownIdGives404HtmlErrorPage() throws Exception {
        assertErrorPage(randomIncidentId());
    }

    private void assertErrorPage(String id) throws Exception {
        String page = html(mvc.perform(get("/incidents/{id}", id).accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML)));

        assertThat(page)
                .contains("class=\"error-status\">404</p>", "class=\"error-reason\">Not Found</p>")
                .contains("href=\"/css/console.css\"", "href=\"/incidents/new\"")
                .doesNotContain("Whitelabel", "Exception", "trace");
    }

    private static String html(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return result.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String storedIncident(Severity severity) {
        return storedIncident(severity, T0);
    }

    private String storedIncident(Severity severity, Instant detectedAt) {
        String id = randomIncidentId();
        incidents.save(new Incident(id, "HighErrorRate on checkout-api", severity, "checkout-api", detectedAt,
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
