package dev.investigator.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.investigator.alert.AlertIntakeService;
import dev.investigator.config.WebhookProperties;
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
import dev.investigator.feedback.Feedback;
import dev.investigator.feedback.FeedbackService;
import dev.investigator.investigation.InvestigationOrchestrator;
import dev.investigator.ratelimit.RateLimiter;
import dev.investigator.report.Approval;
import dev.investigator.report.ApprovalRepository;
import dev.investigator.report.InMemoryApprovalRepository;
import dev.investigator.report.ReportRenderer;
import dev.investigator.store.InMemoryIncidentRepository;
import dev.investigator.store.InMemoryInvestigationRepository;
import dev.investigator.store.InMemoryReportRepository;
import dev.investigator.store.IncidentRepository;
import dev.investigator.store.InvestigationRepository;
import dev.investigator.store.ReportRepository;
import dev.investigator.testsupport.InvestigatorSpringBootTest;
import dev.investigator.testsupport.ScriptedChatModel;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.ViewResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

/** MockMvc examples for the console pages served by {@link IncidentPageController}. */
@InvestigatorSpringBootTest
@AutoConfigureMockMvc
class IncidentPageControllerTest {

    private static final Instant T0 = Instant.parse("2026-11-23T14:40:00Z");
    private static final String DEPLOY_HEADLINE = "checkout-api v2.14.3 deployed";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ApprovalRepository approvals;
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
    @Autowired
    private AlertIntakeService intake;
    @Autowired
    private WebhookProperties webhook;
    @Autowired
    private Validator validator;
    @Autowired
    private Clock clock;
    @Autowired
    private ScriptedChatModel chatModel;
    @Autowired
    private FeedbackService feedbackService;
    @Autowired
    private InvestigationOrchestrator orchestrator;
    /** Real limiter; one test empties the investigation bucket of its own unique service. */
    @MockitoSpyBean
    private RateLimiter rateLimiter;

    /** Incidents whose investigation a form submission in this test started. */
    private final List<String> started = new ArrayList<>();

    /**
     * A valid form submission starts an investigation that calls the shared Scripted_Chat_Model in the
     * background. Waiting for it keeps those calls out of later tests' scripts and recorded requests. Only
     * the investigations this test started are awaited, so incidents other tests leave in progress on
     * purpose do not delay it.
     */
    @AfterEach
    void awaitStartedInvestigations() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline && started.stream()
                .map(investigations::find)
                .anyMatch(i -> i.isEmpty() || i.get().status() == InvestigationStatus.PENDING
                        || i.get().status() == InvestigationStatus.RUNNING)) {
            Thread.sleep(10);
        }
    }

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
                new InMemoryInvestigationRepository(), new InMemoryReportRepository(), renderer, intake, webhook,
                validator, clock, new InMemoryApprovalRepository(), feedbackService, orchestrator))
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
    void newAlertFormListsPresetsAndPrefillsDetectedAtInTheDisplayZone() throws Exception {
        // Mapped to newAlert, not to /incidents/{id} (which would 404 on "new").
        String page = html(mvc.perform(get("/incidents/new")).andExpect(status().isOk()));

        for (ScenarioPreset preset : ScenarioPreset.ALL) {
            assertThat(page)
                    .contains("href=\"/incidents/new?scenario=" + preset.key() + "\"")
                    .contains("value=\"" + preset.key() + "\"");
        }
        assertThat(page)
                .contains("<form id=\"new-alert-form\"", "method=\"post\" action=\"/incidents\"")
                .contains("name=\"alert\"", "name=\"service\"", "name=\"severity\"", "name=\"detectedAt\"",
                        "name=\"scenario\"")
                .contains("value=\"HighErrorRate\"", "value=\"checkout-api\"", "value=\"critical\"")
                .doesNotContain("type=\"datetime-local\"", "id=\"form-disabled\"");
        assertSelectedPreset(page, "payment-gateway");

        Matcher detectedAt = Pattern.compile("name=\"detectedAt\" type=\"text\" value=\"([^\"]*)\"").matcher(page);
        assertThat(detectedAt.find()).as("detectedAt input").isTrue();
        String value = detectedAt.group(1);
        assertThat(value).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(Z|[+-]\\d{2}:\\d{2})");
        OffsetDateTime parsed = OffsetDateTime.parse(value);
        Instant now = clock.instant();
        assertThat(parsed.toInstant()).isBetween(now.minusSeconds(60), now.plusSeconds(1));
        assertThat(parsed.getOffset()).isEqualTo(renderer.times().zone().getRules().getOffset(parsed.toInstant()));
    }

    @Test
    void scenarioParameterPrefillsThatPreset() throws Exception {
        String page = html(mvc.perform(get("/incidents/new").param("scenario", "db-pool-exhaustion"))
                .andExpect(status().isOk()));

        assertSelectedPreset(page, "db-pool-exhaustion");
        assertThat(page).contains("id=\"severity\" name=\"severity\" type=\"text\" list=\"severities\" value=\"high\"");
    }

    @Test
    void validFormRedirectsToTheNewIncidentReportPage() throws Exception {
        chatModel.reset();
        String alert = "WebFormAlert" + randomIncidentId().substring(4);

        String location = mvc.perform(post("/incidents")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("scenario", "bad-deploy")
                        .param("alert", alert)
                        .param("service", "checkout-api")
                        .param("severity", "critical")
                        .param("detectedAt", "2026-11-23T20:10:00+05:30"))
                .andExpect(status().isFound())
                .andReturn().getResponse().getRedirectedUrl();

        assertThat(location).matches("/incidents/inc-[0-9a-f]{12}");
        String id = location.substring("/incidents/".length());
        started.add(id);
        Incident incident = incidents.find(id).orElseThrow();
        assertThat(incident.title()).isEqualTo(alert + " on checkout-api");
        assertThat(incident.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(incident.detectedAt()).isEqualTo(Instant.parse("2026-11-23T14:40:00Z"));
        assertThat(incident.labels()).containsEntry("scenario", "bad-deploy").containsEntry("environment", "production");
    }

    @Test
    void missingFieldsReRenderWith400MessagesAndEnteredValues() throws Exception {
        int before = incidents.findAllNewestFirst().size();

        String page = html(mvc.perform(post("/incidents")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("scenario", "noisy-neighbour")
                        .param("alert", "")
                        .param("service", "   ")
                        .param("severity", "warning")
                        .param("detectedAt", ""))
                .andExpect(status().isBadRequest()));

        assertThat(page)
                .contains("id=\"form-errors\"")
                .contains("<span class=\"ev\">alert</span>: <span>must not be blank</span>")
                .contains("<span class=\"ev\">service</span>: <span>must not be blank</span>")
                .contains("<span class=\"ev\">detectedAt</span>: <span>must not be blank</span>")
                .contains("name=\"service\" type=\"text\" value=\"   \"", "value=\"warning\"")
                .contains("class=\"field-error\">must not be blank</span>");
        assertSelectedPreset(page, "noisy-neighbour");
        assertThat(incidents.findAllNewestFirst()).hasSize(before);
    }

    @Test
    void badSeverityAndOffsetlessTimeReRenderWith400MessagesAndEnteredValues() throws Exception {
        int before = incidents.findAllNewestFirst().size();

        String page = html(mvc.perform(post("/incidents")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("scenario", "bad-deploy")
                        .param("alert", "KeepMe<Alert>")
                        .param("service", "kept-service")
                        .param("severity", "urgent")
                        .param("detectedAt", "2026-11-23T14:40:00"))
                .andExpect(status().isBadRequest()));

        assertThat(page)
                .contains("<span class=\"ev\">severity</span>: <span>must be one of critical, high, warning, info</span>")
                .contains("<span class=\"ev\">detectedAt</span>: <span>must be an ISO-8601 timestamp with a zone offset")
                .contains("value=\"KeepMe&lt;Alert&gt;\"", "value=\"kept-service\"", "value=\"urgent\"",
                        "value=\"2026-11-23T14:40:00\"")
                .doesNotContain("KeepMe<Alert>");
        assertSelectedPreset(page, "bad-deploy");
        assertThat(incidents.findAllNewestFirst()).hasSize(before);
    }

    @Test
    void pendingIncidentShowsInProgressIndicatorAndMetaRefreshWithoutReportSections() throws Exception {
        String id = storedIncident(Severity.CRITICAL);
        investigations.create(pending(id));

        String page = html(mvc.perform(get("/incidents/{id}", id)).andExpect(status().isOk()));

        assertInProgress(page, id, "PENDING");
        assertThat(page).doesNotContain("An earlier report is stored");
    }

    @Test
    void runningReinvestigationWithEarlierReportShowsInProgressAndHidesTheReport() throws Exception {
        String id = storedIncident(Severity.HIGH);
        investigations.create(pending(id));
        investigations.transition(id, InvestigationStatus.PENDING, InvestigationStatus.RUNNING);
        investigations.transition(id, InvestigationStatus.RUNNING, InvestigationStatus.COMPLETED);
        reports.saveIfAbsent(fullReport(id, List.of()));
        assertThat(investigations.restart(pending(id))).isTrue();
        assertThat(investigations.transition(id, InvestigationStatus.PENDING, InvestigationStatus.RUNNING)).isTrue();

        String page = html(mvc.perform(get("/incidents/{id}", id)).andExpect(status().isOk()));

        assertInProgress(page, id, "RUNNING");
        assertThat(page)
                .contains("An earlier report is stored")
                .doesNotContain("checkout errors rose", "payment-provider is slow", "check the provider",
                        "badge-status-COMPLETED");
    }

    @Test
    void completedReportShowsHypothesesCauseLabelsEvidenceLinksAndPendingApproval() throws Exception {
        String id = completedIncidentWithReport(List.of());
        String alertEv = "ev-" + id + "-1";
        String deployEv = "ev-" + id + "-2";

        String page = html(mvc.perform(get("/incidents/{id}", id)).andExpect(status().isOk()));

        assertFinished(page);
        assertThat(page)
                .contains("<span class=\"badge badge-confidence badge-confidence-HIGH\">Confidence: HIGH</span>")
                .contains("<span class=\"badge badge-confidence badge-confidence-LOW\">Confidence: LOW</span>")
                .contains("<span class=\"cause cause-established\">established cause</span>")
                .contains("<span class=\"cause cause-correlated\">correlated, not established</span>")
                .contains(ReportRenderer.correlationStatement(DEPLOY_HEADLINE))
                .contains("Supporting evidence:", "Contradicting evidence:")
                .contains("<tr id=\"ev-" + alertEv + "\" class=\"evidence-entry\">")
                .contains("<tr id=\"ev-" + deployEv + "\" class=\"evidence-entry\">")
                .contains("href=\"#ev-" + alertEv + "\">[" + alertEv + "]</a>")
                .contains("href=\"#ev-" + deployEv + "\">[" + deployEv + "]</a>")
                .contains("[rollback, requires approval]")
                .doesNotContain("evidence-unknown");
        // The established hypothesis comes first, the correlated one second.
        assertThat(page.indexOf("cause-established")).isLessThan(page.indexOf("cause-correlated"));

        String actions = section(page, "actions");
        assertThat(actions)
                .contains("<span class=\"label\">Approval:</span>")
                .contains("<span class=\"badge badge-status-PENDING\">PENDING</span>")
                .doesNotContain("decided by", "badge-status-APPROVED", "badge-status-REJECTED");
        assertThat(actions.split("class=\"approval-state\"", -1)).as("one approvable action").hasSize(2);
    }

    @Test
    void unknownCitedEvidenceIdRendersAsPlainTextWithoutLink() throws Exception {
        String id = storedIncident(Severity.CRITICAL);
        finishedInvestigation(id, InvestigationStatus.COMPLETED);
        String unknown = "ev-" + id + "-99";
        reports.saveIfAbsent(fullReport(id, List.of(unknown)));

        String page = html(mvc.perform(get("/incidents/{id}", id)).andExpect(status().isOk()));

        assertThat(page)
                .contains("<span class=\"ev evidence-unknown\">[" + unknown + "]</span>")
                .doesNotContain("href=\"#ev-" + unknown + "\"", "id=\"ev-" + unknown + "\"");
    }

    @Test
    void decidedApprovalShowsStateDeciderAndTime() throws Exception {
        Instant decidedAt = T0.plusSeconds(300);
        String id = completedIncidentWithReport(List.of());
        // "#20 " must not match action #2; the newest "#2 " approval wins over the older pending one.
        approvals.save(Approval.pending(randomApprovalId(), id, "#20 ROLLBACK: another action", T0.plusSeconds(200)));
        approvals.save(Approval.pending(randomApprovalId(), id, "#2 ROLLBACK: roll back v2.14.3", T0.plusSeconds(60)));
        String newest = randomApprovalId();
        approvals.save(Approval.pending(newest, id, "#2 ROLLBACK: roll back v2.14.3", T0.plusSeconds(120)));
        assertThat(approvals.decide(newest, Approval.Status.APPROVED, "alice", decidedAt)).isTrue();

        String page = html(mvc.perform(get("/incidents/{id}", id)).andExpect(status().isOk()));

        String actions = section(page, "actions");
        assertThat(actions)
                .contains("<span class=\"badge badge-status-APPROVED\">APPROVED</span>")
                .contains("decided by <span class=\"decided-by\">alice</span>")
                .contains(" at <span class=\"decided-at\">" + renderer.times().dateTime(decidedAt) + "</span>")
                .doesNotContain("badge-status-PENDING");
    }

    @Test
    void finishedPagesHaveNoMetaRefreshAndStartNoPoll() throws Exception {
        for (InvestigationStatus status : List.of(InvestigationStatus.COMPLETED, InvestigationStatus.INCONCLUSIVE,
                InvestigationStatus.FAILED)) {
            String id = storedIncident(Severity.WARNING);
            finishedInvestigation(id, status);

            String page = html(mvc.perform(get("/incidents/{id}", id)).andExpect(status().isOk()));

            assertFinished(page);
            assertThat(page).as(status.name())
                    .contains("class=\"badge badge-status-" + status.name() + "\">" + status.name() + "</span>");
        }
    }

    @Test
    void failedInvestigationWithoutReportShowsFailureMessage() throws Exception {
        String id = storedIncident(Severity.CRITICAL);
        finishedInvestigation(id, InvestigationStatus.FAILED);

        String page = html(mvc.perform(get("/incidents/{id}", id)).andExpect(status().isOk()));

        assertFinished(page);
        assertThat(page)
                .contains("id=\"investigation-failed\"", "The investigation failed and no report was produced.")
                .doesNotContain("id=\"summary\"", "id=\"hypotheses\"", "id=\"evidence\"");
        // The Reinvestigate_Control is offered without a report; feedback and download are not (Req 4.6, 8.1).
        assertThat(page)
                .contains("<form id=\"reinvestigate-form\" method=\"post\" action=\"/incidents/" + id
                        + "/reinvestigate\">")
                .doesNotContain("id=\"feedback-form\"", "report.md");
    }

    @Test
    void feedbackPostStoresItAndThePageShowsTheConfirmationAndTheEntry() throws Exception {
        String id = completedIncidentWithReport(List.of());

        MvcResult result = mvc.perform(post("/incidents/{id}/feedback", id)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("rating", "helpful")
                        .param("note", "Spot on <b>deploy</b>"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/incidents/" + id + "#feedback"))
                .andExpect(flash().attribute(IncidentPageController.FLASH_NOTICE,
                        IncidentPageController.FEEDBACK_RECORDED_MESSAGE))
                .andReturn();

        List<Feedback> stored = feedbackService.list(id);
        assertThat(stored).singleElement().satisfies(f -> {
            assertThat(f.rating()).isEqualTo(Feedback.Rating.HELPFUL);
            assertThat(f.note()).isEqualTo("Spot on <b>deploy</b>");
        });
        String page = followRedirect(result);
        assertThat(page).contains("id=\"flash-notice\"", IncidentPageController.FEEDBACK_RECORDED_MESSAGE)
                .doesNotContain("id=\"flash-error\"");
        assertThat(page.indexOf("id=\"flash-notice\"")).as("flash near the top").isLessThan(page.indexOf("<dl"));
        String section = section(page, "feedback");
        assertThat(section)
                .contains("type=\"radio\" name=\"rating\" value=\"helpful\"",
                        "type=\"radio\" name=\"rating\" value=\"unhelpful\"")
                .contains("name=\"note\" maxlength=\"2000\"")
                .contains("action=\"/incidents/" + id + "/feedback\"")
                .contains("<span class=\"feedback-rating feedback-helpful\">helpful</span>")
                .contains("<span class=\"ev feedback-time\">"
                        + renderer.times().dateTime(stored.get(0).createdAt()) + "</span>")
                .contains("<span class=\"feedback-note\">Spot on &lt;b&gt;deploy&lt;/b&gt;</span>")
                .doesNotContain("No feedback yet.", "<b>deploy");
    }

    @Test
    void tooLongFeedbackNoteShowsAMessageAndStoresNothing() throws Exception {
        String id = completedIncidentWithReport(List.of());

        MvcResult result = mvc.perform(post("/incidents/{id}/feedback", id)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("rating", "unhelpful")
                        .param("note", "x".repeat(FeedbackService.MAX_NOTE + 1)))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/incidents/" + id + "#feedback"))
                .andExpect(flash().attribute(IncidentPageController.FLASH_ERROR,
                        IncidentPageController.FEEDBACK_NOTE_MESSAGE))
                .andReturn();

        assertThat(feedbackService.list(id)).isEmpty();
        String page = followRedirect(result);
        assertThat(page).contains("id=\"flash-error\"", IncidentPageController.FEEDBACK_NOTE_MESSAGE)
                .doesNotContain("id=\"flash-notice\"");
        assertThat(section(page, "feedback")).contains("No feedback yet.");
    }

    @Test
    void feedbackOnAnIncidentWithoutReportIs404() throws Exception {
        String id = storedIncident(Severity.CRITICAL);
        finishedInvestigation(id, InvestigationStatus.FAILED);

        String page = html(mvc.perform(post("/incidents/{id}/feedback", id)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("rating", "helpful"))
                .andExpect(status().isNotFound()));

        assertThat(page).contains("class=\"error-status\">404</p>");
        assertThat(feedbackService.list(id)).isEmpty();
    }

    @Test
    void reinvestigateRedirectsAndThePageThenShowsTheInProgressState() throws Exception {
        // Hold the re-investigation at its first model call so the page is rendered while it is RUNNING.
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        chatModel.reset().enqueue(request -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ScriptedChatModel.finalAnswer(ScriptedChatModel.DEFAULT_FINAL_ANSWER).apply(request);
        });
        String id = completedIncidentWithReport(List.of());
        try {
            MvcResult result = mvc.perform(post("/incidents/{id}/reinvestigate", id))
                    .andExpect(status().isFound())
                    .andExpect(redirectedUrl("/incidents/" + id))
                    .andExpect(flash().attribute(IncidentPageController.FLASH_NOTICE,
                            IncidentPageController.REINVESTIGATION_STARTED_MESSAGE))
                    .andReturn();
            started.add(id);
            assertThat(entered.await(10, TimeUnit.SECONDS)).as("re-investigation reached the model").isTrue();

            String page = followRedirect(result);

            assertInProgress(page, id, "RUNNING");
            assertThat(page).contains("id=\"flash-notice\"", IncidentPageController.REINVESTIGATION_STARTED_MESSAGE)
                    .contains("An earlier report is stored");
        } finally {
            release.countDown();
        }
    }

    @Test
    void reinvestigateWhileRunningShowsTheConflictMessageAndKeepsTheReport() throws Exception {
        // An earlier report plus a RUNNING re-investigation held through the repository, not a real run.
        String id = completedIncidentWithReport(List.of());
        assertThat(investigations.restart(pending(id))).isTrue();
        assertThat(investigations.transition(id, InvestigationStatus.PENDING, InvestigationStatus.RUNNING)).isTrue();
        InvestigationReport before = reports.find(id).orElseThrow();

        MvcResult result = mvc.perform(post("/incidents/{id}/reinvestigate", id))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/incidents/" + id))
                .andExpect(flash().attribute(IncidentPageController.FLASH_ERROR,
                        IncidentPageController.REINVESTIGATION_CONFLICT_MESSAGE))
                .andReturn();

        assertThat(reports.find(id)).contains(before);
        assertThat(investigations.find(id).orElseThrow().status()).isEqualTo(InvestigationStatus.RUNNING);
        String page = followRedirect(result);
        assertThat(page).contains("id=\"flash-error\"", IncidentPageController.REINVESTIGATION_CONFLICT_MESSAGE);
        assertInProgress(page, id, "RUNNING");
    }

    @Test
    void rateLimitedReinvestigateShowsTheRateLimitMessageAndKeepsTheReport() throws Exception {
        String service = "rl-" + randomIncidentId().substring(4);
        String id = storedIncident(Severity.CRITICAL, T0, service);
        finishedInvestigation(id, InvestigationStatus.COMPLETED);
        reports.saveIfAbsent(fullReport(id, List.of()));
        doReturn(false).when(rateLimiter).tryInvestigation(service);

        MvcResult result = mvc.perform(post("/incidents/{id}/reinvestigate", id))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/incidents/" + id))
                .andExpect(flash().attribute(IncidentPageController.FLASH_ERROR,
                        IncidentPageController.REINVESTIGATION_RATE_LIMITED_MESSAGE))
                .andReturn();

        assertThat(investigations.find(id).orElseThrow().status()).isEqualTo(InvestigationStatus.COMPLETED);
        String page = followRedirect(result);
        assertFinished(page);
        assertThat(page).contains("id=\"flash-error\"", IncidentPageController.REINVESTIGATION_RATE_LIMITED_MESSAGE)
                .contains("checkout errors rose", "badge-status-COMPLETED");
    }

    @Test
    void finishedReportOffersMarkdownDownloadAndReinvestigateControl() throws Exception {
        String id = completedIncidentWithReport(List.of());

        String page = html(mvc.perform(get("/incidents/{id}", id)).andExpect(status().isOk()));

        assertThat(section(page, "report-controls"))
                .contains("<form id=\"reinvestigate-form\" method=\"post\" action=\"/incidents/" + id
                        + "/reinvestigate\">")
                .contains("id=\"report-download\" href=\"/api/v1/incidents/" + id + "/report.md\"");
        assertThat(page).contains("id=\"feedback-form\"")
                .doesNotContain("id=\"flash-notice\"", "id=\"flash-error\"");
    }

    /** Follows a Post-Redirect-Get redirect, carrying the flash attributes as the browser session would. */
    private String followRedirect(MvcResult result) throws Exception {
        String url = result.getResponse().getRedirectedUrl();
        assertThat(url).isNotNull();
        int hash = url.indexOf('#');
        String path = hash < 0 ? url : url.substring(0, hash);
        return html(mvc.perform(get(path).flashAttrs(result.getFlashMap())).andExpect(status().isOk()));
    }

    private static void assertInProgress(String page, String id, String status) {
        assertThat(page)
                .contains("<meta http-equiv=\"refresh\" content=\"5\">")
                .contains("id=\"in-progress\"", "Investigation in progress.")
                .contains("data-live-report", "data-incident-id=\"" + id + "\"")
                .contains("class=\"badge badge-status-" + status + "\">" + status + "</span>")
                .doesNotContain("id=\"summary\"", "id=\"hypotheses\"", "id=\"timeline\"", "id=\"actions\"",
                        "id=\"open-questions\"", "id=\"evidence\"", "id=\"ev-", "What happened",
                        "Recommended next steps", "id=\"investigation-failed\"")
                // Feedback, download and re-investigate are hidden until the investigation finishes (Req 8.1, 8.6).
                .doesNotContain("id=\"report-controls\"", "id=\"reinvestigate-form\"", "id=\"feedback\"",
                        "id=\"feedback-form\"", "report.md");
    }

    private static void assertFinished(String page) {
        assertThat(page)
                .doesNotContain("http-equiv=\"refresh\"", "data-live-report", "data-incident-id", "id=\"in-progress\"");
    }

    /** The markup of {@code <section id="name" ...>} up to its closing tag. */
    private static String section(String page, String name) {
        int start = page.indexOf("<section id=\"" + name + "\"");
        assertThat(start).as("section %s", name).isNotNegative();
        return page.substring(start, page.indexOf("</section>", start));
    }

    private void finishedInvestigation(String id, InvestigationStatus status) {
        investigations.create(pending(id));
        assertThat(investigations.transition(id, InvestigationStatus.PENDING, InvestigationStatus.RUNNING)).isTrue();
        assertThat(investigations.transition(id, InvestigationStatus.RUNNING, status)).isTrue();
    }

    private String completedIncidentWithReport(List<String> extraCitations) {
        String id = storedIncident(Severity.CRITICAL);
        finishedInvestigation(id, InvestigationStatus.COMPLETED);
        reports.saveIfAbsent(fullReport(id, extraCitations));
        return id;
    }

    /**
     * A report with an alert and a deploy Evidence_Entry, an established and a correlated hypothesis, and an
     * investigate action plus an approvable rollback (#2); {@code extraCitations} are added to the timeline.
     */
    private static InvestigationReport fullReport(String id, List<String> extraCitations) {
        String alertEv = "ev-" + id + "-1";
        String deployEv = "ev-" + id + "-2";
        List<String> timelineIds = new ArrayList<>(List.of(alertEv));
        timelineIds.addAll(extraCitations);
        return new InvestigationReport(id, InvestigationStatus.COMPLETED, "checkout errors rose",
                new IncidentTimeline(List.of(new TimelineEntry(T0, "alert fires", timelineIds))),
                List.of(new Hypothesis("payment-provider is slow", Confidence.HIGH, List.of(alertEv), List.of(), true),
                        new Hypothesis("the deploy caused it", Confidence.LOW, List.of(deployEv), List.of(alertEv),
                                false)),
                List.of(new RecommendedAction(1, ActionType.INVESTIGATE, "check the provider", false,
                                List.of(alertEv)),
                        new RecommendedAction(2, ActionType.ROLLBACK, "roll back v2.14.3", true, List.of(deployEv))),
                List.of(), List.of(new EvidenceRef(alertEv, EvidenceType.ALERT, "alert-intake", T0,
                                "HighErrorRate fired", "alert payload"),
                        new EvidenceRef(deployEv, EvidenceType.DEPLOYMENT, "github", T0.minusSeconds(840),
                                DEPLOY_HEADLINE, "deployment 1000001")),
                T0.plusSeconds(60));
    }

    private static String randomApprovalId() {
        byte[] bytes = new byte[6];
        ThreadLocalRandom.current().nextBytes(bytes);
        return "apr-" + HexFormat.of().formatHex(bytes);
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

    /** The scenario {@code <select>} has exactly one selected option, the one for {@code key}. */
    private static void assertSelectedPreset(String page, String key) {
        Matcher selected = Pattern.compile("<option value=\"([^\"]*)\"[^>]*\\sselected=\"selected\"").matcher(page);
        List<String> keys = new ArrayList<>();
        while (selected.find()) {
            keys.add(selected.group(1));
        }
        assertThat(keys).as("selected scenario options").containsExactly(key);
    }

    private static String html(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return result.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String storedIncident(Severity severity) {
        return storedIncident(severity, T0);
    }

    private String storedIncident(Severity severity, Instant detectedAt) {
        return storedIncident(severity, detectedAt, "checkout-api");
    }

    private String storedIncident(Severity severity, Instant detectedAt, String service) {
        String id = randomIncidentId();
        incidents.save(new Incident(id, "HighErrorRate on checkout-api", severity, service, detectedAt,
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
