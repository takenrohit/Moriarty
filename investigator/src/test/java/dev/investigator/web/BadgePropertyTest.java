// Feature: investigator-web-ui, Property 1: Badge renders its value as text with a stable, distinct class
package dev.investigator.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.investigator.domain.Incident;
import dev.investigator.domain.IncidentStatus;
import dev.investigator.domain.IncidentTimeline;
import dev.investigator.domain.Investigation;
import dev.investigator.domain.InvestigationStatus;
import dev.investigator.domain.Severity;
import dev.investigator.store.IncidentRepository;
import dev.investigator.store.InvestigationRepository;
import dev.investigator.testsupport.InvestigatorSpringBootTest;
import dev.investigator.testsupport.PropertySeeds;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Property 1: for any Investigation_Status or Severity value, the badge on the rendered Report_Page shows the
 * value's name as text and carries the class mapped to that value (defined in {@code console.css}), and
 * distinct values map to distinct classes.
 *
 * <p><b>Validates: Requirements 1.3, 1.4</b>
 */
@InvestigatorSpringBootTest
@AutoConfigureMockMvc
class BadgePropertyTest {

    private static final Instant T0 = Instant.parse("2026-11-23T14:40:00Z");
    private static final Pattern BADGE =
            Pattern.compile("<span class=\"badge (badge-(?:status|severity)-[^\"]*)\">([^<]*)</span>");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private IncidentRepository incidents;
    @Autowired
    private InvestigationRepository investigations;

    @ParameterizedTest(name = "seed={0}")
    @MethodSource("dev.investigator.testsupport.PropertySeeds#seeds")
    void badgeShowsValueAsTextWithStableDistinctClass(long seed) {
        PropertySeeds.check(seed, random -> {
            String css = mvc.perform(get("/css/console.css")).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

            InvestigationStatus statusA = pick(random, InvestigationStatus.values());
            InvestigationStatus statusB = pick(random, InvestigationStatus.values());
            Severity severityA = pick(random, Severity.values());
            Severity severityB = pick(random, Severity.values());

            Rendered a = render(statusA, severityA);
            Rendered b = render(statusB, severityB);

            for (Rendered r : List.of(a, b)) {
                assertThat(r.statusBadges()).as("status badges").isNotEmpty()
                        .allSatisfy(badge -> {
                            assertThat(badge.text()).isEqualTo(r.status().name());
                            assertThat(badge.cssClass()).isEqualTo("badge-status-" + r.status().name());
                        });
                assertThat(r.severityBadges()).as("severity badges").hasSize(1)
                        .allSatisfy(badge -> {
                            assertThat(badge.text()).isEqualTo(display(r.severity()));
                            assertThat(badge.cssClass()).isEqualTo("badge-severity-" + display(r.severity()));
                        });
                assertThat(css).contains("." + r.statusBadges().get(0).cssClass() + " ",
                        "." + r.severityBadges().get(0).cssClass() + " ");
                assertThat(r.statusBadges().get(0).cssClass()).isNotEqualTo(r.severityBadges().get(0).cssClass());
            }

            // Stable and distinct: two renders share a class exactly when they show the same value.
            assertThat(a.statusBadges().get(0).cssClass().equals(b.statusBadges().get(0).cssClass()))
                    .isEqualTo(statusA == statusB);
            assertThat(a.severityBadges().get(0).cssClass().equals(b.severityBadges().get(0).cssClass()))
                    .isEqualTo(severityA == severityB);
        });
    }

    private Rendered render(InvestigationStatus investigationStatus, Severity severity) throws Exception {
        String id = storedIncident(severity, investigationStatus);
        String page = mvc.perform(get("/incidents/{id}", id)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<Badge> statusBadges = new ArrayList<>();
        List<Badge> severityBadges = new ArrayList<>();
        Matcher m = BADGE.matcher(page);
        while (m.find()) {
            Badge badge = new Badge(m.group(1), m.group(2));
            (badge.cssClass().startsWith("badge-status-") ? statusBadges : severityBadges).add(badge);
        }
        return new Rendered(investigationStatus, severity, statusBadges, severityBadges);
    }

    private String storedIncident(Severity severity, InvestigationStatus target) {
        String id = randomIncidentId();
        incidents.save(new Incident(id, "HighErrorRate on checkout-api", severity, "checkout-api", T0,
                "HighErrorRate", null, null, id.substring(4) + "0".repeat(52), IncidentStatus.REPORTED));
        investigations.create(new Investigation(id, InvestigationStatus.PENDING, T0.minusSeconds(3600),
                T0.plusSeconds(600), 0, List.of(), List.of(), new IncidentTimeline(List.of()), List.of()));
        if (target != InvestigationStatus.PENDING) {
            assertThat(investigations.transition(id, InvestigationStatus.PENDING, InvestigationStatus.RUNNING))
                    .isTrue();
        }
        if (target != InvestigationStatus.PENDING && target != InvestigationStatus.RUNNING) {
            assertThat(investigations.transition(id, InvestigationStatus.RUNNING, target)).isTrue();
        }
        return id;
    }

    /** The readable Severity name shown on the pages, e.g. {@code High}. */
    private static String display(Severity severity) {
        String lower = severity.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static <T> T pick(SplittableRandom random, T[] values) {
        return values[random.nextInt(values.length)];
    }

    private static String randomIncidentId() {
        byte[] bytes = new byte[6];
        ThreadLocalRandom.current().nextBytes(bytes);
        return "inc-" + HexFormat.of().formatHex(bytes);
    }

    private record Badge(String cssClass, String text) {
    }

    private record Rendered(InvestigationStatus status, Severity severity, List<Badge> statusBadges,
            List<Badge> severityBadges) {
    }
}
