// Feature: investigator-web-ui, Property 10: Untrusted text is always HTML-escaped in rendered pages
package dev.investigator.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import dev.investigator.feedback.Feedback;
import dev.investigator.feedback.FeedbackService;
import dev.investigator.report.Approval;
import dev.investigator.report.ApprovalRepository;
import dev.investigator.report.ReportRenderer;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * Property 10: for any Incident, report, feedback note and decided Approval whose text fields (title,
 * service, summary, timeline description, hypothesis statement, correlation statement, action description,
 * open question, evidence headline, citation and source, feedback note, decider name, dashboard link) carry
 * HTML and script payloads, every page that shows them ({@code /incidents}, {@code /incidents/fragment},
 * {@code /incidents/{id}}) prints the HTML-escaped text and no payload becomes live markup: every {@code <}
 * on the page starts a well-formed tag of the templates' own vocabulary, no attribute is an event handler,
 * the only script is {@code /js/console.js}, and no {@code href}, {@code src} or {@code action} is a
 * {@code javascript:} URL or a dashboard link that fails the http(s) check.
 * <b>Validates: Requirements 9.1, 10.5</b>
 *
 * <p>Data is written straight to the stores under a fresh random incident id per seed (no investigation is
 * started, so no Scripted_Chat_Model calls happen). The list pages show every stored Incident of the shared
 * Spring context, so their markup checks cover the other tests' Incidents too.
 */
@InvestigatorSpringBootTest
@AutoConfigureMockMvc
class HtmlEscapingPropertyTest {

    private static final Instant T0 = Instant.parse("2026-11-23T14:40:00Z");

    /** Element names the console templates emit; a payload tag such as img, svg, iframe or b is not here. */
    private static final Set<String> TEMPLATE_TAGS = Set.of("a", "body", "button", "code", "datalist", "dd", "div",
            "dl", "dt", "fieldset", "footer", "form", "h1", "h2", "h3", "head", "header", "html", "input", "label",
            "legend", "li", "link", "main", "meta", "nav", "ol", "option", "p", "script", "section", "select",
            "span", "table", "tbody", "td", "textarea", "th", "thead", "title", "tr", "ul");
    /** URL-carrying attributes, checked for {@code javascript:} and unvalidated external targets. */
    private static final Set<String> URL_ATTRIBUTES = Set.of("href", "src", "action", "formaction");
    /** A complete tag whose attributes are bare names or double-quoted values without raw markup. */
    private static final Pattern TAG = Pattern.compile(
            "<(/?)([a-zA-Z][a-zA-Z0-9]*)((?:\\s+[a-zA-Z_:][-a-zA-Z0-9_:.]*(?:=\"[^\"<>]*\")?)*)\\s*/?>");
    private static final Pattern ATTRIBUTE = Pattern.compile("([a-zA-Z_:][-a-zA-Z0-9_:.]*)(?:=\"([^\"]*)\")?");
    private static final String DOCTYPE = "<!DOCTYPE html>";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private IncidentRepository incidents;
    @Autowired
    private InvestigationRepository investigations;
    @Autowired
    private ReportRepository reports;
    @Autowired
    private ApprovalRepository approvals;
    @Autowired
    private FeedbackService feedbackService;

    @ParameterizedTest(name = "seed={0}")
    @MethodSource("dev.investigator.testsupport.PropertySeeds#seeds")
    void untrustedTextIsAlwaysHtmlEscapedInRenderedPages(long seed) {
        PropertySeeds.check(seed, random -> {
            String id = freshIncidentId();
            String nonce = id.substring(4);
            Map<String, String> text = new LinkedHashMap<>();
            for (String field : List.of("title", "service", "alertName", "summary", "timeline", "hypothesis1",
                    "hypothesis2", "action1", "action2", "question", "source1", "headline1", "citation1", "source2",
                    "headline2", "citation2", "note", "decider")) {
                // A per-field, per-seed label makes each value unique on the page.
                text.put(field, field + "-" + nonce + " " + DomainGenerators.htmlPayloadText(random));
            }
            List<String> dashboardLinks = dashboardLinks(random, nonce);
            Map<String, String> labels = new LinkedHashMap<>();
            labels.put("scenario", DomainGenerators.htmlPayloadText(random));
            labels.put(DomainGenerators.htmlPayloadText(random), DomainGenerators.htmlPayloadText(random));

            incidents.save(new Incident(id, text.get("title"), DomainGenerators.pick(random, Severity.values()),
                    text.get("service"), T0, text.get("alertName"), labels, dashboardLinks,
                    nonce + "0".repeat(52), IncidentStatus.REPORTED));
            reports.saveIfAbsent(report(id, text, random));
            investigation(id);
            Feedback.Rating rating = random.nextBoolean() ? Feedback.Rating.HELPFUL : Feedback.Rating.UNHELPFUL;
            // One payload keeps the submitter (not shown on the page) within FeedbackService.MAX_SUBMITTER.
            String submitter = DomainGenerators.pick(random, DomainGenerators.HTML_PAYLOADS);
            assertThat(feedbackService.submit(id, rating, text.get("note"), submitter)).as("feedback stored")
                    .isPresent();
            String approvalId = randomApprovalId();
            approvals.save(Approval.pending(approvalId, id, "#1 ROLLBACK: " + text.get("action1"), T0.plusSeconds(60)));
            Approval.Status decision = random.nextBoolean() ? Approval.Status.APPROVED : Approval.Status.REJECTED;
            assertThat(approvals.decide(approvalId, decision, text.get("decider"), T0.plusSeconds(120))).isTrue();

            String list = html("/incidents");
            String fragment = html("/incidents/fragment");
            String detail = html("/incidents/" + id);

            assertNoLiveMarkup(list, "/incidents", 1, List.of());
            assertNoLiveMarkup(fragment, "/incidents/fragment", 0, List.of());
            assertThat(fragment.strip()).as("fragment is the table body").startsWith("<tbody id=\"incident-rows\">");
            for (String field : List.of("title", "service")) {
                assertEscaped(list, "/incidents " + field, text.get(field));
                assertEscaped(fragment, "/incidents/fragment " + field, text.get(field));
            }

            assertNoLiveMarkup(detail, "/incidents/" + id, 1, dashboardLinks);
            for (Map.Entry<String, String> e : text.entrySet()) {
                if (!e.getKey().equals("alertName")) {
                    assertEscaped(detail, "/incidents/" + id + " " + e.getKey(), e.getValue());
                }
            }
            assertEscaped(detail, "correlation statement",
                    ReportRenderer.correlationStatement(text.get("headline2")));
            assertThat(detail).as("decider shown escaped")
                    .contains("<span class=\"decided-by\">" + escape(text.get("decider")) + "</span>");
            assertThat(detail).as("note shown escaped")
                    .contains("<span class=\"feedback-note\">" + escape(text.get("note")) + "</span>");
            assertThat(occurrences(detail, "<textarea")).as("only the feedback note textarea").isEqualTo(1);
            for (String link : dashboardLinks) {
                assertEscaped(detail, "dashboard link", link);
                boolean linkable = HttpUrlValidator.isHttpUrl(link);
                assertThat(detail.contains("href=\"" + escape(link) + "\""))
                        .as("dashboard link %s is an href exactly when it passes the http(s) check", link)
                        .isEqualTo(linkable);
            }
        });
    }

    /**
     * Every {@code <} on the page must open a well-formed tag (or the doctype): escaped text has no raw
     * {@code <}, so any other one is payload markup. Each tag must be one the templates emit, carry no event
     * handler, and have only safe URL attributes; the one script is the console's own, with no inline body.
     */
    private static void assertNoLiveMarkup(String page, String where, int expectedScripts,
            List<String> dashboardLinks) {
        int scripts = 0;
        Matcher tag = TAG.matcher(page);
        for (int at = page.indexOf('<'); at >= 0; at = page.indexOf('<', at + 1)) {
            if (page.startsWith(DOCTYPE, at)) {
                continue;
            }
            tag.region(at, page.length());
            if (!tag.lookingAt()) {
                fail("%s: raw markup at %d: %s", where, at, context(page, at));
            }
            String name = tag.group(2).toLowerCase(Locale.ROOT);
            assertThat(TEMPLATE_TAGS).as("%s: tag <%s> at %s", where, name, context(page, at)).contains(name);
            if (tag.group(1).isEmpty() && name.equals("script")) {
                scripts++;
                assertThat(page.startsWith("</script>", tag.end())).as("%s: script has no inline body", where).isTrue();
            }
            Matcher attribute = ATTRIBUTE.matcher(tag.group(3));
            while (attribute.find()) {
                String attr = attribute.group(1).toLowerCase(Locale.ROOT);
                String value = attribute.group(2) == null ? "" : HtmlUtils.htmlUnescape(attribute.group(2));
                String shown = context(page, at);
                assertThat(attr).as("%s: event handler attribute in %s", where, shown).doesNotStartWith("on");
                assertThat(attr).as("%s: srcdoc in %s", where, shown).isNotEqualTo("srcdoc");
                if (name.equals("script") && attr.equals("src")) {
                    assertThat(value).as("%s: script source", where).isEqualTo("/js/console.js");
                }
                if (URL_ATTRIBUTES.contains(attr)) {
                    String url = value.strip().toLowerCase(Locale.ROOT);
                    assertThat(url).as("%s: %s in %s", where, attr, shown)
                            .doesNotStartWith("javascript:").doesNotStartWith("vbscript:").doesNotStartWith("data:");
                    if (!value.startsWith("/") && !value.startsWith("#")) {
                        assertThat(HttpUrlValidator.isHttpUrl(value) && dashboardLinks.contains(value))
                                .as("%s: external %s=%s is a validated dashboard link", where, attr, value).isTrue();
                    }
                }
            }
        }
        assertThat(scripts).as("%s: only the console script", where).isEqualTo(expectedScripts);
    }

    /**
     * The escaped form of {@code value} is on the page and, when escaping changes it, the raw form is not.
     * The raw check runs after removing the escaped occurrences, since a raw value can be a prefix of its own
     * escaped form (raw {@code x &amp;} inside escaped {@code x &amp;amp;}).
     */
    private static void assertEscaped(String page, String where, String value) {
        String escaped = escape(value);
        assertThat(page).as("%s: escaped %s", where, value).contains(escaped);
        if (!escaped.equals(value)) {
            assertThat(page.replace(escaped, "")).as("%s: raw %s", where, value).doesNotContain(value);
        }
    }

    /** Thymeleaf's {@code th:text} escaping: the five markup-significant characters only. */
    private static String escape(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (char c : value.toCharArray()) {
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Two to four dashboard links: always a {@code javascript:} URL and one with raw payload text (both fail
     * the http(s) check), plus, sometimes, a valid https link with an encoded payload and {@code &} in the
     * query, and a mixed-case {@code JaVaScRiPt:} URL.
     */
    private static List<String> dashboardLinks(SplittableRandom random, String nonce) {
        List<String> links = new ArrayList<>();
        links.add("javascript:alert(" + nonce.length() + ")//" + nonce);
        // The space alone makes URI.create reject it, whatever the payload.
        links.add("https://grafana.example.com/d/" + nonce + " " + DomainGenerators.htmlPayloadText(random));
        if (random.nextBoolean()) {
            links.add("https://grafana.example.com/d/" + nonce + "?q=%22%3E%3Cscript%3Ealert(1)%3C/script%3E&from=now-1h");
        }
        if (random.nextBoolean()) {
            links.add("JaVaScRiPt:alert(document.cookie)/" + nonce);
        }
        return links;
    }

    /**
     * A {@code COMPLETED} report with every text field from {@code text}: an ALERT and a DEPLOYMENT entry; the
     * first hypothesis (not established) cites the deployment, so its headline also yields a
     * Correlation_Statement; action 1 is a ROLLBACK needing approval, action 2 an INVESTIGATE step.
     */
    private static InvestigationReport report(String id, Map<String, String> text, SplittableRandom random) {
        String ev1 = "ev-" + id + "-1";
        String ev2 = "ev-" + id + "-2";
        List<EvidenceRef> evidence = List.of(
                new EvidenceRef(ev1, EvidenceType.ALERT, text.get("source1"), T0, text.get("headline1"),
                        text.get("citation1")),
                new EvidenceRef(ev2, EvidenceType.DEPLOYMENT, text.get("source2"), T0.minusSeconds(300),
                        text.get("headline2"), text.get("citation2")));
        List<Hypothesis> hypotheses = List.of(
                new Hypothesis(text.get("hypothesis1"), DomainGenerators.pick(random, Confidence.values()),
                        List.of(ev2), List.of(), false),
                new Hypothesis(text.get("hypothesis2"), DomainGenerators.pick(random, Confidence.values()),
                        List.of(ev1), List.of(ev2), true));
        List<RecommendedAction> actions = List.of(
                new RecommendedAction(1, ActionType.ROLLBACK, text.get("action1"), true, List.of(ev2)),
                new RecommendedAction(2, ActionType.INVESTIGATE, text.get("action2"), false, List.of(ev1)));
        return new InvestigationReport(id, InvestigationStatus.COMPLETED, text.get("summary"),
                new IncidentTimeline(List.of(new TimelineEntry(T0, text.get("timeline"), List.of(ev1, ev2)))),
                hypotheses, actions, List.of(text.get("question")), evidence, T0.plusSeconds(60));
    }

    /** {@code GET path} as HTML, expecting 200. */
    private String html(String path) throws Exception {
        return mvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** Creates the Investigation and moves it PENDING, RUNNING, COMPLETED. */
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

    private static String randomApprovalId() {
        byte[] bytes = new byte[6];
        ThreadLocalRandom.current().nextBytes(bytes);
        return "apr-" + HexFormat.of().formatHex(bytes);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    private static String context(String page, int at) {
        return page.substring(Math.max(0, at - 40), Math.min(page.length(), at + 80));
    }
}
