// Feature: investigator-web-ui, Property 2: Incident list rows mirror the store order and link to the report
package dev.investigator.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.investigator.alert.AlertIntakeService;
import dev.investigator.config.WebhookProperties;
import dev.investigator.domain.Incident;
import dev.investigator.domain.IncidentStatus;
import dev.investigator.domain.Severity;
import dev.investigator.feedback.FeedbackService;
import dev.investigator.investigation.InvestigationOrchestrator;
import dev.investigator.report.InMemoryApprovalRepository;
import dev.investigator.report.ReportRenderer;
import dev.investigator.store.InMemoryIncidentRepository;
import dev.investigator.store.InMemoryInvestigationRepository;
import dev.investigator.store.InMemoryReportRepository;
import dev.investigator.testsupport.InvestigatorSpringBootTest;
import dev.investigator.testsupport.PropertySeeds;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.ViewResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

/**
 * Property 2: for any set of Incidents, the Incident_List_Page and its refresh fragment render exactly one
 * row per Incident, in the order of {@code findAllNewestFirst}, and every link in a row goes to
 * {@code /incidents/{id}} for that row's Incident.
 *
 * <p>Each seed renders through a standalone MockMvc over fresh in-memory stores (the shared context's stores
 * hold other tests' incidents and cannot be cleared), with the application's own Thymeleaf view resolver.
 *
 * <p><b>Validates: Requirements 2.1, 2.2</b>
 */
@InvestigatorSpringBootTest
class IncidentListPropertyTest {

    private static final Instant T0 = Instant.parse("2026-11-23T14:40:00Z");
    private static final String ROW_START = "<tr class=\"incident-row\">";
    private static final Pattern HREF = Pattern.compile("href=\"([^\"]*)\"");
    private static final Pattern ID_CELL = Pattern.compile("<td class=\"ev\"><a href=\"[^\"]*\">([^<]*)</a>");

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
    // Not exercised by the list pages; the shared context's beans satisfy the constructor.
    @Autowired
    private FeedbackService feedback;
    @Autowired
    private InvestigationOrchestrator orchestrator;

    @ParameterizedTest(name = "seed={0}")
    @MethodSource("dev.investigator.testsupport.PropertySeeds#seeds")
    void listRowsMirrorStoreOrderAndLinkToReport(long seed) {
        PropertySeeds.check(seed, random -> {
            InMemoryIncidentRepository store = new InMemoryIncidentRepository();
            for (Incident incident : incidents(random)) {
                store.save(incident);
            }
            List<String> expected = store.findAllNewestFirst().stream().map(Incident::id).toList();

            MockMvc mvc = MockMvcBuilders.standaloneSetup(new IncidentPageController(store,
                    new InMemoryInvestigationRepository(), new InMemoryReportRepository(), renderer, intake, webhook,
                    validator, clock, new InMemoryApprovalRepository(), feedback, orchestrator))
                    // Delegate so the standalone builder does not re-bind the shared resolver to its stub context.
                    .setViewResolvers((ViewResolver) viewResolver::resolveViewName)
                    .build();

            for (String path : List.of("/incidents", "/incidents/fragment")) {
                String html = mvc.perform(get(path)).andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
                assertThat(rowIds(html)).as("row ids of %s", path).containsExactlyElementsOf(expected);
            }
        });
    }

    /**
     * The Incident id of each {@code incident-row} in document order, after checking that the row shows that
     * id and that every link in the row (at least one) points to {@code /incidents/{id}}.
     */
    private static List<String> rowIds(String html) {
        List<String> ids = new ArrayList<>();
        int at = html.indexOf(ROW_START);
        while (at >= 0) {
            int end = html.indexOf("</tr>", at);
            assertThat(end).as("row end").isPositive();
            String row = html.substring(at, end);
            Matcher idCell = ID_CELL.matcher(row);
            assertThat(idCell.find()).as("id cell in %s", row).isTrue();
            String id = idCell.group(1);
            List<String> hrefs = new ArrayList<>();
            Matcher href = HREF.matcher(row);
            while (href.find()) {
                hrefs.add(href.group(1));
            }
            assertThat(hrefs).as("links in row %s", id).isNotEmpty().containsOnly("/incidents/" + id);
            ids.add(id);
            at = html.indexOf(ROW_START, end);
        }
        return ids;
    }

    /** 0 to 15 Incidents with distinct ids and detectedAt drawn from a few instants, so ties are common. */
    private static List<Incident> incidents(SplittableRandom random) {
        int count = random.nextInt(16);
        int distinctTimes = 1 + random.nextInt(Math.max(1, count));
        Set<String> ids = new HashSet<>();
        List<Incident> result = new ArrayList<>();
        while (result.size() < count) {
            String id = "inc-" + String.format(Locale.ROOT, "%012x", random.nextLong(1L << 48));
            if (!ids.add(id)) {
                continue;
            }
            Instant detectedAt = T0.plus(Duration.ofMinutes(random.nextInt(distinctTimes)))
                    .plusSeconds(random.nextInt(2) == 0 ? 0 : random.nextInt(3600 * 24 * 30));
            Severity severity = Severity.values()[random.nextInt(Severity.values().length)];
            result.add(new Incident(id, "Alert " + result.size() + " on svc-" + random.nextInt(4), severity,
                    "svc-" + random.nextInt(4), detectedAt, "HighErrorRate", null, null,
                    id.substring(4) + "0".repeat(52), IncidentStatus.OPEN));
        }
        return result;
    }
}
