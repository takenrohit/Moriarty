// Feature: investigator-web-ui, Property 5: Report in-progress state and no-JS refresh follow the status
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
import dev.investigator.store.IncidentRepository;
import dev.investigator.store.InvestigationRepository;
import dev.investigator.store.ReportRepository;
import dev.investigator.testsupport.InvestigatorSpringBootTest;
import dev.investigator.testsupport.PropertySeeds;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Properties of the Report_Page ({@code GET /incidents/{id}}).
 *
 * <p>Property 5: for any Incident whose Investigation_Status is not a Finished_Status, whether or not an
 * earlier report version is stored, the page shows the in-progress indicator in place of the report sections
 * and carries the 5-second meta refresh and the live-poll marker; for any Finished_Status both are absent.
 * <b>Validates: Requirements 4.2, 4.4, 4.5</b>
 *
 * <p>All seeds share the Spring context of the other {@code @InvestigatorSpringBootTest} MockMvc tests and
 * store their data under fresh random incident ids, so seeds and property methods never collide. Data is
 * written straight to the stores (no investigation is started), so no Scripted_Chat_Model calls happen.
 */
@InvestigatorSpringBootTest
@AutoConfigureMockMvc
class ReportPagePropertyTest {

    private static final Instant T0 = Instant.parse("2026-11-23T14:40:00Z");
    private static final List<InvestigationStatus> FINISHED = List.of(InvestigationStatus.COMPLETED,
            InvestigationStatus.INCONCLUSIVE, InvestigationStatus.FAILED);
    private static final String META_REFRESH = "<meta http-equiv=\"refresh\" content=\"5\">";
    /** Markers of the report sections, which the in-progress indicator replaces. */
    private static final List<String> REPORT_SECTIONS = List.of("id=\"summary\"", "id=\"hypotheses\"",
            "id=\"timeline\"", "id=\"actions\"", "id=\"open-questions\"", "id=\"evidence\"", "id=\"ev-",
            "id=\"investigation-failed\"");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private IncidentRepository incidents;
    @Autowired
    private InvestigationRepository investigations;
    @Autowired
    private ReportRepository reports;

    @ParameterizedTest(name = "seed={0}")
    @MethodSource("dev.investigator.testsupport.PropertySeeds#seeds")
    void inProgressStateAndMetaRefreshFollowTheStatus(long seed) {
        PropertySeeds.check(seed, random -> {
            InvestigationStatus target = pick(random, InvestigationStatus.values());
            boolean withReport = random.nextBoolean();
            String id = storedIncident(random);
            if (withReport) {
                reports.saveIfAbsent(report(id, random));
                investigationThen(id, target);
            } else {
                investigation(id, target);
            }

            String page = page(id);
            String badge = "class=\"badge badge-status-" + target.name() + "\">" + target.name() + "</span>";

            if (FINISHED.contains(target)) {
                assertThat(page).as("%s, report=%s", target, withReport)
                        .contains(badge)
                        .doesNotContain("http-equiv=\"refresh\"", "data-live-report", "data-incident-id",
                                "id=\"in-progress\"");
                if (withReport) {
                    assertThat(page).as("report sections once %s", target).contains("id=\"summary\"");
                }
            } else {
                assertThat(page).as("%s, report=%s", target, withReport)
                        .contains(META_REFRESH, "id=\"in-progress\"", "Investigation in progress.")
                        .contains("data-live-report", "data-incident-id=\"" + id + "\"", badge)
                        .doesNotContain(REPORT_SECTIONS.toArray(String[]::new));
                assertThat(page.contains("An earlier report is stored")).as("earlier-report note").isEqualTo(withReport);
            }
        });
    }

    // ---- shared helpers for the Report_Page properties ----

    /** {@code GET /incidents/{id}} as HTML, expecting 200. */
    private String page(String id) throws Exception {
        return mvc.perform(get("/incidents/{id}", id))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** Stores an Incident under a fresh random id with a generated severity and returns the id. */
    private String storedIncident(SplittableRandom random) {
        String id;
        do {
            id = randomIncidentId();
        } while (incidents.find(id).isPresent());
        Severity severity = pick(random, Severity.values());
        incidents.save(new Incident(id, "HighErrorRate on checkout-api", severity, "checkout-api", T0,
                "HighErrorRate", null, null, id.substring(4) + "0".repeat(52), IncidentStatus.REPORTED));
        return id;
    }

    /** Creates the first Investigation and moves it to {@code target} through allowed transitions. */
    private void investigation(String id, InvestigationStatus target) {
        investigations.create(pending(id));
        advance(id, target);
    }

    /**
     * Runs a first Investigation to {@code COMPLETED} (the stored report's round), then restarts it as a
     * re-investigation and moves that to {@code target}.
     */
    private void investigationThen(String id, InvestigationStatus target) {
        investigation(id, InvestigationStatus.COMPLETED);
        assertThat(investigations.restart(pending(id))).as("restart %s", id).isTrue();
        advance(id, target);
    }

    private void advance(String id, InvestigationStatus target) {
        if (target == InvestigationStatus.PENDING) {
            return;
        }
        assertThat(investigations.transition(id, InvestigationStatus.PENDING, InvestigationStatus.RUNNING)).isTrue();
        if (target != InvestigationStatus.RUNNING) {
            assertThat(investigations.transition(id, InvestigationStatus.RUNNING, target)).isTrue();
        }
    }

    /**
     * A report with 1 to 3 Evidence_Entries, one hypothesis and one action per entry citing it, and a
     * timeline entry citing all of them.
     */
    private static InvestigationReport report(String id, SplittableRandom random) {
        int count = 1 + random.nextInt(3);
        List<EvidenceRef> evidence = new ArrayList<>();
        List<Hypothesis> hypotheses = new ArrayList<>();
        List<RecommendedAction> actions = new ArrayList<>();
        List<String> evidenceIds = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            String ev = "ev-" + id + "-" + i;
            evidenceIds.add(ev);
            evidence.add(new EvidenceRef(ev, EvidenceType.ALERT, "alert-intake", T0.plusSeconds(i),
                    "finding " + i, "citation " + i));
            hypotheses.add(new Hypothesis("hypothesis " + i, pick(random, Confidence.values()), List.of(ev),
                    List.of(), random.nextBoolean()));
            actions.add(new RecommendedAction(i, ActionType.INVESTIGATE, "check " + i, false, List.of(ev)));
        }
        InvestigationStatus status = random.nextBoolean() ? InvestigationStatus.COMPLETED
                : InvestigationStatus.INCONCLUSIVE;
        return new InvestigationReport(id, status, "summary of " + id,
                new IncidentTimeline(List.of(new TimelineEntry(T0, "alert fires", evidenceIds))), hypotheses,
                actions, List.of(), evidence, T0.plusSeconds(60));
    }

    private static Investigation pending(String id) {
        return new Investigation(id, InvestigationStatus.PENDING, T0.minusSeconds(3600), T0.plusSeconds(600), 0,
                List.of(), List.of(), new IncidentTimeline(List.of()), List.of());
    }

    private static <T> T pick(SplittableRandom random, T[] values) {
        return values[random.nextInt(values.length)];
    }

    private static String randomIncidentId() {
        byte[] bytes = new byte[6];
        ThreadLocalRandom.current().nextBytes(bytes);
        return "inc-" + HexFormat.of().formatHex(bytes);
    }
}
