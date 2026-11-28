// Feature: investigator-web-ui, Property 11: Only validated identifiers appear in URL attributes
package dev.investigator.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.investigator.alert.IncidentIdGenerator;
import dev.investigator.api.HttpUrlValidator;
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
import dev.investigator.testsupport.DomainGenerators;
import dev.investigator.testsupport.InvestigatorSpringBootTest;
import dev.investigator.testsupport.PropertySeeds;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.HtmlUtils;

/**
 * Property 11: for any Incident and report whose text fields carry HTML payloads and whose dashboard links
 * mix valid http(s) URLs, {@code javascript:} URLs and garbage, every {@code href}, {@code src},
 * {@code action} and {@code formaction} on {@code /incidents}, {@code /incidents/fragment} and
 * {@code /incidents/{id}} is a console path built from a pattern-valid incident id, a {@code #ev-} anchor
 * to an Evidence_ID in this report, a {@code /css} or {@code /js} asset, or a stored dashboard link that
 * passes {@link HttpUrlValidator#isHttpUrl}. <b>Validates: Requirements 9.3</b>
 *
 * <p>Data is written straight to the stores under a fresh incident id per seed; the list pages show every
 * stored Incident of the shared Spring context, so their checks cover the other tests' Incidents too.
 */
@InvestigatorSpringBootTest
@AutoConfigureMockMvc
class UrlAttributePropertyTest {

    private static final Instant T0 = Instant.parse("2026-11-23T14:40:00Z");
    private static final String ID = IncidentIdGenerator.ID_PATTERN.pattern();
    private static final Pattern CONSOLE_PATH =
            Pattern.compile("/incidents(?:/new|/" + ID + "(?:/reinvestigate|/feedback)?)?");
    private static final Pattern API_PATH = Pattern.compile("/api/v1/incidents/" + ID + "(?:/[a-z]+(?:\\.md)?)?");
    private static final Pattern ASSET = Pattern.compile("/(?:css|js)/[a-z0-9-]+\\.(?:css|js)");
    /** A double-quoted URL attribute; escaped page text has no raw {@code "}, so this only hits real attributes. */
    private static final Pattern URL_ATTRIBUTE =
            Pattern.compile("(?i)[\\s\"](href|src|action|formaction)\\s*=\\s*\"([^\"]*)\"");

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
    void onlyValidatedIdentifiersAppearInUrlAttributes(long seed) {
        PropertySeeds.check(seed, random -> {
            String id = freshIncidentId();
            String nonce = id.substring(4);
            List<String> links = dashboardLinks(random, nonce);
            incidents.save(new Incident(id, untrusted(random), DomainGenerators.pick(random, Severity.values()),
                    untrusted(random), T0, untrusted(random), Map.of("scenario", untrusted(random)), links,
                    nonce + "0".repeat(52), IncidentStatus.REPORTED));
            InvestigationReport report = report(id, random);
            reports.saveIfAbsent(report);
            investigation(id);
            Set<String> evidenceIds = Set.copyOf(report.evidence().stream().map(EvidenceRef::id).toList());

            assertUrlAttributes(html("/incidents"), "/incidents", Set.of(), List.of());
            assertUrlAttributes(html("/incidents/fragment"), "/incidents/fragment", Set.of(), List.of());
            String detail = html("/incidents/" + id);
            assertUrlAttributes(detail, "/incidents/" + id, evidenceIds, links);
            for (String evId : evidenceIds) {
                assertThat(detail).as("evidence anchor rendered").contains("href=\"#ev-" + evId + "\"");
            }
            for (String link : links) {
                assertThat(detail.contains("href=\"" + HtmlUtils.htmlEscape(link) + "\""))
                        .as("dashboard link %s linked exactly when valid", link)
                        .isEqualTo(HttpUrlValidator.isHttpUrl(link));
            }
        });
    }

    private static void assertUrlAttributes(String page, String where, Set<String> evidenceIds, List<String> links) {
        Matcher m = URL_ATTRIBUTE.matcher(page);
        int count = 0;
        while (m.find()) {
            count++;
            String value = HtmlUtils.htmlUnescape(m.group(2));
            boolean allowed = CONSOLE_PATH.matcher(value).matches()
                    || API_PATH.matcher(value).matches()
                    || ASSET.matcher(value).matches()
                    || (value.startsWith("#ev-") && evidenceIds.contains(value.substring(4)))
                    || (HttpUrlValidator.isHttpUrl(value) && links.contains(value));
            assertThat(allowed).as("%s: %s=\"%s\"", where, m.group(1), value).isTrue();
        }
        assertThat(count).as("%s: URL attributes found", where).isPositive();
    }

    private static String untrusted(SplittableRandom random) {
        return DomainGenerators.htmlPayloadText(random);
    }

    /** One to four links: valid http(s) (with encoded payload), {@code javascript:}, and unparseable garbage. */
    private static List<String> dashboardLinks(SplittableRandom random, String nonce) {
        List<String> candidates = List.of(
                "https://grafana.example.com/d/" + nonce + "?q=%22%3E%3Cscript%3E&from=now-1h",
                "http://localhost:3000/d/" + nonce,
                "javascript:alert(1)//" + nonce,
                " JaVaScRiPt:alert(document.cookie)/" + nonce,
                "/incidents/" + nonce + " " + DomainGenerators.htmlPayloadText(random),
                "https://grafana.example.com/d/" + nonce + " " + DomainGenerators.htmlPayloadText(random),
                "data:text/html," + DomainGenerators.htmlPayloadText(random),
                "ftp://example.com/" + nonce,
                DomainGenerators.htmlPayloadText(random));
        List<String> links = new ArrayList<>();
        int n = 1 + random.nextInt(4);
        for (int i = 0; i < n; i++) {
            String link = DomainGenerators.pick(random, candidates);
            if (!links.contains(link)) {
                links.add(link);
            }
        }
        return links;
    }

    /**
     * A {@code COMPLETED} report with payload text everywhere; hypotheses and actions also cite an unknown
     * Evidence_ID made of payload text, which must not become an anchor.
     */
    private static InvestigationReport report(String id, SplittableRandom random) {
        String ev1 = "ev-" + id + "-1";
        String ev2 = "ev-" + id + "-2";
        String unknown = "ev-" + untrusted(random);
        List<EvidenceRef> evidence = List.of(
                new EvidenceRef(ev1, EvidenceType.ALERT, untrusted(random), T0, untrusted(random), untrusted(random)),
                new EvidenceRef(ev2, EvidenceType.DEPLOYMENT, untrusted(random), T0.minusSeconds(300),
                        untrusted(random), untrusted(random)));
        List<Hypothesis> hypotheses = List.of(
                new Hypothesis(untrusted(random), DomainGenerators.pick(random, Confidence.values()),
                        List.of(ev2, unknown), List.of(), false),
                new Hypothesis(untrusted(random), DomainGenerators.pick(random, Confidence.values()),
                        List.of(ev1), List.of(ev2, unknown), true));
        List<RecommendedAction> actions = List.of(
                new RecommendedAction(1, ActionType.ROLLBACK, untrusted(random), true, List.of(ev2)),
                new RecommendedAction(2, ActionType.INVESTIGATE, untrusted(random), false, List.of(ev1, unknown)));
        return new InvestigationReport(id, InvestigationStatus.COMPLETED, untrusted(random),
                new IncidentTimeline(List.of(new TimelineEntry(T0, untrusted(random), List.of(ev1, unknown)))),
                hypotheses, actions, List.of(untrusted(random)), evidence, T0.plusSeconds(60));
    }

    private String html(String path) throws Exception {
        return mvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private void investigation(String id) {
        investigations.create(new Investigation(id, InvestigationStatus.PENDING, T0.minusSeconds(3600),
                T0.plusSeconds(600), 0, List.of(), List.of(), new IncidentTimeline(List.of()), List.of()));
        assertThat(investigations.transition(id, InvestigationStatus.PENDING, InvestigationStatus.RUNNING)).isTrue();
        assertThat(investigations.transition(id, InvestigationStatus.RUNNING, InvestigationStatus.COMPLETED)).isTrue();
    }

    private String freshIncidentId() {
        String id;
        do {
            byte[] bytes = new byte[6];
            ThreadLocalRandom.current().nextBytes(bytes);
            id = "inc-" + HexFormat.of().formatHex(bytes);
        } while (incidents.find(id).isPresent());
        return id;
    }
}
