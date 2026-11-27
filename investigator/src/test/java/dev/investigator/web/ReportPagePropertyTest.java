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
import dev.investigator.feedback.Feedback;
import dev.investigator.feedback.FeedbackService;
import dev.investigator.report.ActionPolicy;
import dev.investigator.report.Approval;
import dev.investigator.report.ApprovalRepository;
import dev.investigator.report.ReportRenderer;
import dev.investigator.store.IncidentRepository;
import dev.investigator.store.InvestigationRepository;
import dev.investigator.store.ReportRepository;
import dev.investigator.testsupport.InvestigatorSpringBootTest;
import dev.investigator.testsupport.PropertySeeds;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
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
 * Properties of the Report_Page ({@code GET /incidents/{id}}).
 *
 * <p>Property 5: for any Incident whose Investigation_Status is not a Finished_Status, whether or not an
 * earlier report version is stored, the page shows the in-progress indicator in place of the report sections
 * and carries the 5-second meta refresh and the live-poll marker; for any Finished_Status both are absent.
 * <b>Validates: Requirements 4.2, 4.4, 4.5</b>
 *
 * <p>Property 6: for any hypothesis on a finished Report_Page, its confidence is shown as a labelled badge
 * and its cause status reads exactly {@code established cause} when {@code establishedCause} is true and
 * {@code correlated, not established} otherwise, never both.
 * <b>Validates: Requirements 5.1, 5.2</b>
 *
 * <p>Property 7: for any report whose timeline, hypotheses and actions cite a mix of Evidence_IDs that match
 * an Evidence_Entry and well-formed ids that match none, every entry has exactly one anchor
 * {@code ev-<evidenceId>}, every known citation links to {@code #ev-<evidenceId>}, and every unknown
 * citation is plain text that no {@code href} contains.
 * <b>Validates: Requirements 6.1, 6.2, 6.4, 7.1</b>
 *
 * <p>Property 8: for any recommended action that requires approval, the page shows the state of the newest
 * Approval for that Incident and action, the decider and decision time exactly when that state is
 * {@code APPROVED} or {@code REJECTED}, and {@code PENDING} when no Approval exists for it.
 * <b>Validates: Requirements 7.2, 7.3, 7.4</b>
 *
 * <p>Property 9: for any sequence of 0 to 6 feedback submissions (random rating, note with blanks and HTML
 * metacharacters, submitter) on a {@code COMPLETED} report, the page lists them newest first, that is in
 * reverse submission order, each with its rating, its stripped note HTML-escaped (none when blank) and its
 * time in the Display_Timezone, or {@code No feedback yet.} when there are none.
 * <b>Validates: Requirements 8.3</b>
 *
 * <p>Property 12: for any Incident with a Finished_Status, the page has exactly one link whose target is
 * {@code /api/v1/incidents/{id}/report.md} built from that Incident's id when a report is stored, and that
 * URL serves 200 {@code text/markdown}; without a stored report there is no download link.
 * <b>Validates: Requirements 8.6</b>
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
    /** Cause-status labels of a hypothesis (Req 5.2). */
    private static final String ESTABLISHED = "established cause";
    private static final String CORRELATED = "correlated, not established";
    /** Every {@code href} attribute value on a page. */
    private static final Pattern HREF = Pattern.compile("href=\"([^\"]*)\"");
    /** Action types that require approval under the ActionPolicy. */
    private static final ActionType[] APPROVABLE = {ActionType.ROLLBACK, ActionType.RESTART,
            ActionType.TRAFFIC_SHIFT, ActionType.CONFIG_CHANGE};
    /** The text of a recorded feedback note on the page. */
    private static final Pattern FEEDBACK_NOTE = Pattern.compile("<span class=\"feedback-note\">([^<]*)</span>");
    /** Feedback note fragments, including blanks, padding and HTML metacharacters. */
    private static final String[] NOTE_FRAGMENTS = {"", "   ", " padded ", "<script>alert('x')</script>",
            "\"><img src=x onerror=alert(1)>", "a & b", "it's \"quoted\"", "<b>bold</b>", "&amp;&lt;",
            "caf\u00e9 <\u00e9>", "rollback helped"};

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
    private ReportRenderer renderer;
    @Autowired
    private FeedbackService feedbackService;

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

    // Feature: investigator-web-ui, Property 6: Hypotheses show confidence and an unambiguous cause status
    @ParameterizedTest(name = "seed={0}")
    @MethodSource("dev.investigator.testsupport.PropertySeeds#seeds")
    void hypothesesShowConfidenceAndExactlyOneCauseLabel(long seed) {
        PropertySeeds.check(seed, random -> {
            String id = storedIncident(random);
            InvestigationReport report = report(id, random, 1 + random.nextInt(5));
            reports.saveIfAbsent(report);
            investigation(id, InvestigationStatus.COMPLETED);

            String page = page(id);
            int start = page.indexOf("id=\"hypotheses\"");
            int end = page.indexOf("id=\"timeline\"");
            assertThat(start).as("hypotheses section").isNotNegative();
            assertThat(end).as("timeline section after hypotheses").isGreaterThan(start);
            String[] items = page.substring(start, end).split("class=\"hypothesis\"", -1);
            List<Hypothesis> hypotheses = report.hypotheses();
            assertThat(items).as("one list item per hypothesis").hasSize(hypotheses.size() + 1);

            for (int i = 0; i < hypotheses.size(); i++) {
                Hypothesis h = hypotheses.get(i);
                String item = items[i + 1];
                String confidence = h.confidence().name();
                String expected = h.establishedCause() ? ESTABLISHED : CORRELATED;
                String other = h.establishedCause() ? CORRELATED : ESTABLISHED;
                assertThat(item).as("hypothesis %d confidence badge", i + 1)
                        .contains("class=\"badge badge-confidence badge-confidence-" + confidence + "\">Confidence: "
                                + confidence + "</span>");
                assertThat(occurrences(item, ">" + expected + "</span>")).as("hypothesis %d label %s", i + 1, expected)
                        .isEqualTo(1);
                assertThat(occurrences(item, ">" + other + "</span>")).as("hypothesis %d label %s", i + 1, other)
                        .isZero();
            }
        });
    }

    // Feature: investigator-web-ui, Property 7: Evidence ids link exactly when a matching entry exists
    @ParameterizedTest(name = "seed={0}")
    @MethodSource("dev.investigator.testsupport.PropertySeeds#seeds")
    void evidenceIdsLinkExactlyWhenAMatchingEntryExists(long seed) {
        PropertySeeds.check(seed, random -> {
            String id = storedIncident(random);
            int count = 1 + random.nextInt(5);
            List<String> known = new ArrayList<>();
            for (int i = 1; i <= count; i++) {
                known.add("ev-" + id + "-" + i);
            }
            // Well-formed ids of the same incident numbered above every entry, so no known id contains one.
            List<String> unknown = new ArrayList<>();
            int n = count + 1 + random.nextInt(5);
            for (int k = 1 + random.nextInt(3); k > 0; k--) {
                unknown.add("ev-" + id + "-" + n);
                n += 1 + random.nextInt(7);
            }
            List<String> cited = new ArrayList<>();
            InvestigationReport report = citingReport(id, random, known, unknown, cited);
            reports.saveIfAbsent(report);
            investigation(id, InvestigationStatus.COMPLETED);

            String page = page(id);
            for (String ev : known) {
                assertThat(occurrences(page, "id=\"ev-" + ev + "\"")).as("anchor of %s", ev).isEqualTo(1);
                assertThat(occurrences(page, "<a class=\"ev evidence-link\" href=\"#ev-" + ev + "\">[" + ev + "]</a>"))
                        .as("links to %s", ev).isEqualTo(Collections.frequency(cited, ev));
                assertThat(page).as("known %s never plain", ev)
                        .doesNotContain("<span class=\"ev evidence-unknown\">[" + ev + "]</span>");
            }
            List<String> hrefs = new ArrayList<>();
            Matcher m = HREF.matcher(page);
            while (m.find()) {
                hrefs.add(m.group(1));
            }
            for (String ev : unknown) {
                assertThat(occurrences(page, "<span class=\"ev evidence-unknown\">[" + ev + "]</span>"))
                        .as("plain text of %s", ev).isEqualTo(Collections.frequency(cited, ev));
                assertThat(page).as("no anchor for %s", ev).doesNotContain("id=\"ev-" + ev + "\"");
                assertThat(hrefs).as("no href with %s", ev).noneMatch(h -> h.contains(ev));
            }
            long knownCites = cited.stream().filter(known::contains).count();
            assertThat(occurrences(page, "class=\"ev evidence-link\"")).as("all links").isEqualTo((int) knownCites);
            assertThat(occurrences(page, "class=\"ev evidence-unknown\"")).as("all plain ids")
                    .isEqualTo(cited.size() - (int) knownCites);
        });
    }

    // Feature: investigator-web-ui, Property 8: Approval state reflects the newest matching approval, defaulting to PENDING
    @ParameterizedTest(name = "seed={0}")
    @MethodSource("dev.investigator.testsupport.PropertySeeds#seeds")
    void approvalStateReflectsTheNewestMatchingApproval(long seed) {
        PropertySeeds.check(seed, random -> {
            String id = storedIncident(random);
            String ev = "ev-" + id + "-1";
            List<RecommendedAction> actions = new ArrayList<>();
            for (int order = 1, n = 2 + random.nextInt(5); order <= n; order++) {
                // The first action always needs approval; the rest mix every type.
                ActionType type = order == 1 ? pick(random, APPROVABLE) : pick(random, ActionType.values());
                actions.add(new RecommendedAction(order, type, "step " + order, ActionPolicy.requiresApproval(type),
                        List.of(ev)));
            }
            InvestigationReport report = new InvestigationReport(id, InvestigationStatus.COMPLETED,
                    "summary of " + id, new IncidentTimeline(List.of(new TimelineEntry(T0, "alert fires", List.of(ev)))),
                    List.of(), actions, List.of(),
                    List.of(new EvidenceRef(ev, EvidenceType.ALERT, "alert-intake", T0, "finding", "citation")),
                    T0.plusSeconds(60));

            // Per approvable action, 0 to 3 requests at distinct times, each left PENDING or decided; plus,
            // sometimes, a newer decided decoy for order "<order><digit>", which must never match "#<order> ".
            List<Approval> history = new ArrayList<>();
            Map<Integer, Approval> newest = new HashMap<>();
            for (RecommendedAction action : actions) {
                if (!action.requiresApproval()) {
                    continue;
                }
                List<Integer> offsets = new ArrayList<>(List.of(60, 120, 180, 240, 300, 360));
                Collections.shuffle(offsets, new Random(random.nextLong()));
                for (int k = random.nextInt(4), i = 0; i < k; i++) {
                    Approval a = Approval.pending(randomApprovalId(), id,
                            "#" + action.order() + " " + action.type().name() + ": step " + action.order(),
                            T0.plusSeconds(offsets.get(i)));
                    if (random.nextBoolean()) {
                        a = a.decided(random.nextBoolean() ? Approval.Status.APPROVED : Approval.Status.REJECTED,
                                decider(random), a.requestedAt().plusSeconds(1 + random.nextInt(86_400)));
                    }
                    history.add(a);
                    newest.merge(action.order(), a, (x, y) -> y.requestedAt().isAfter(x.requestedAt()) ? y : x);
                }
                if (random.nextBoolean()) {
                    history.add(Approval.pending(randomApprovalId(), id,
                            "#" + action.order() + random.nextInt(10) + " ROLLBACK: decoy", T0.plusSeconds(900))
                            .decided(Approval.Status.APPROVED, "decoy-user", T0.plusSeconds(960)));
                }
            }
            // Written in random order through the repository's real request and decision methods.
            Collections.shuffle(history, new Random(random.nextLong()));
            for (Approval a : history) {
                approvals.save(Approval.pending(a.id(), id, a.action(), a.requestedAt()));
                if (a.status() != Approval.Status.PENDING) {
                    assertThat(approvals.decide(a.id(), a.status(), a.decidedBy(), a.decidedAt())).isTrue();
                }
            }
            reports.saveIfAbsent(report);
            investigation(id, InvestigationStatus.COMPLETED);

            String page = page(id);
            int start = page.indexOf("id=\"actions\"");
            int end = page.indexOf("id=\"open-questions\"");
            assertThat(start).as("actions section").isNotNegative();
            assertThat(end).as("open questions after actions").isGreaterThan(start);
            String[] items = page.substring(start, end).split("class=\"action\"", -1);
            assertThat(items).as("one list item per action").hasSize(actions.size() + 1);

            for (RecommendedAction action : actions) {
                String item = items[action.order()];
                int order = action.order();
                if (!action.requiresApproval()) {
                    assertThat(item).as("action %d (%s) has no approval state", order, action.type())
                            .doesNotContain("approval-state", "badge-status-", "decided by");
                    continue;
                }
                Approval match = newest.get(order);
                String state = match == null ? "PENDING" : match.status().name();
                assertThat(occurrences(item, "class=\"badge badge-status-")).as("action %d one badge", order)
                        .isEqualTo(1);
                assertThat(item).as("action %d state", order)
                        .contains("class=\"approval-state\"")
                        .contains("<span class=\"badge badge-status-" + state + "\">" + state + "</span>")
                        .doesNotContain("decoy-user");
                if (match != null && match.status() != Approval.Status.PENDING) {
                    assertThat(item).as("action %d decider and time", order)
                            .contains("decided by <span class=\"decided-by\">" + match.decidedBy() + "</span>")
                            .contains(" at <span class=\"decided-at\">" + renderer.times().dateTime(match.decidedAt())
                                    + "</span>");
                } else {
                    assertThat(item).as("action %d undecided", order)
                            .doesNotContain("decided by", "class=\"decided-by\"", "class=\"decided-at\"");
                }
            }
        });
    }

    // Feature: investigator-web-ui, Property 9: Recorded feedback is listed newest first
    @ParameterizedTest(name = "seed={0}")
    @MethodSource("dev.investigator.testsupport.PropertySeeds#seeds")
    void recordedFeedbackIsListedNewestFirst(long seed) {
        PropertySeeds.check(seed, random -> {
            String id = storedIncident(random);
            InvestigationReport base = report(id, random);
            reports.saveIfAbsent(new InvestigationReport(id, InvestigationStatus.COMPLETED, base.summary(),
                    base.timeline(), base.hypotheses(), base.actions(), base.openQuestions(),
                    base.evidence(), base.generatedAt()));
            investigation(id, InvestigationStatus.COMPLETED);

            // Submitted through the real Feedback_Service, oldest first; the page must show them reversed.
            List<Feedback> submitted = new ArrayList<>();
            List<String> expectedNotes = new ArrayList<>();
            for (int i = random.nextInt(7); i > 0; i--) {
                Feedback.Rating rating = random.nextBoolean() ? Feedback.Rating.HELPFUL : Feedback.Rating.UNHELPFUL;
                String note = feedbackNote(random);
                Feedback stored = feedbackService.submit(id, rating, note, submitter(random)).orElseThrow();
                submitted.add(stored);
                expectedNotes.add(note.isBlank() ? null : note.strip());
            }

            String page = page(id);
            int start = page.indexOf("<section id=\"feedback\"");
            assertThat(start).as("feedback section").isNotNegative();
            String section = page.substring(start, page.indexOf("</section>", start));
            if (submitted.isEmpty()) {
                assertThat(section).as("no feedback").contains("No feedback yet.").doesNotContain("feedback-entry");
                return;
            }
            assertThat(section).as("feedback list").doesNotContain("No feedback yet.");
            String[] items = section.split("class=\"feedback-entry\"", -1);
            assertThat(items).as("one list item per feedback").hasSize(submitted.size() + 1);

            for (int shown = 0; shown < submitted.size(); shown++) {
                int index = submitted.size() - 1 - shown;
                Feedback f = submitted.get(index);
                String item = items[shown + 1];
                String rating = f.rating().name().toLowerCase(Locale.ROOT);
                assertThat(item).as("entry %d is submission %d: rating", shown + 1, index + 1)
                        .contains("<span class=\"feedback-rating feedback-" + rating + "\">" + rating + "</span>");
                assertThat(item).as("entry %d is submission %d: time", shown + 1, index + 1)
                        .contains("<span class=\"ev feedback-time\">" + renderer.times().dateTime(f.createdAt())
                                + "</span>");
                Matcher note = FEEDBACK_NOTE.matcher(item);
                String expected = expectedNotes.get(index);
                if (expected == null) {
                    assertThat(note.find()).as("entry %d has no note", shown + 1).isFalse();
                } else {
                    assertThat(note.find()).as("entry %d note", shown + 1).isTrue();
                    String raw = note.group(1);
                    assertThat(raw).as("entry %d note escaped", shown + 1).doesNotContain("<", ">");
                    assertThat(HtmlUtils.htmlUnescape(raw)).as("entry %d is submission %d: note", shown + 1, index + 1)
                            .isEqualTo(expected);
                    assertThat(note.find()).as("entry %d one note", shown + 1).isFalse();
                }
            }
        });
    }

    // Feature: investigator-web-ui, Property 12: The Markdown download link targets the report endpoint
    @ParameterizedTest(name = "seed={0}")
    @MethodSource("dev.investigator.testsupport.PropertySeeds#seeds")
    void markdownDownloadLinkTargetsTheReportEndpoint(long seed) {
        PropertySeeds.check(seed, random -> {
            InvestigationStatus target = pick(random, FINISHED.toArray(InvestigationStatus[]::new));
            boolean withReport = random.nextBoolean();
            String id = storedIncident(random);
            if (withReport) {
                reports.saveIfAbsent(report(id, random));
            }
            investigation(id, target);

            String page = page(id);
            List<String> downloads = new ArrayList<>();
            Matcher m = HREF.matcher(page);
            while (m.find()) {
                String href = HtmlUtils.htmlUnescape(m.group(1));
                if (href.contains("report.md")) {
                    downloads.add(href);
                }
            }
            assertThat(page).as("re-investigate form once %s", target).contains("id=\"reinvestigate-form\"");
            if (!withReport) {
                assertThat(downloads).as("no download link without a report (%s)", target).isEmpty();
                assertThat(page).as("no download anchor without a report").doesNotContain("id=\"report-download\"");
                return;
            }
            String expected = "/api/v1/incidents/" + id + "/report.md";
            assertThat(downloads).as("exactly one download link to %s (%s)", expected, target)
                    .containsExactly(expected);
            assertThat(occurrences(page, "id=\"report-download\"")).as("one download anchor").isEqualTo(1);

            String markdown = mvc.perform(get(downloads.get(0)))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("text/markdown"))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(markdown).as("markdown of %s", id).isNotBlank();
        });
    }

    /** A note of 0 to 3 fragments: plain words, padding, blanks and HTML metacharacters. */
    private static String feedbackNote(SplittableRandom random) {
        StringBuilder note = new StringBuilder();
        for (int k = random.nextInt(4); k > 0; k--) {
            note.append(random.nextBoolean() ? pick(random, NOTE_FRAGMENTS) : decider(random));
        }
        return note.toString();
    }

    /** No submitter, a blank one, a generated name or HTML text; the page does not show it. */
    private static String submitter(SplittableRandom random) {
        return switch (random.nextInt(4)) {
            case 0 -> null;
            case 1 -> "  ";
            case 2 -> decider(random);
            default -> "<b>" + decider(random) + "</b> & co";
        };
    }

    /** A generated decider name of lowercase letters, digits, dots and hyphens (no HTML-special text). */
    private static String decider(SplittableRandom random) {
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789.-";
        StringBuilder name = new StringBuilder("u");
        for (int i = random.nextInt(12); i >= 0; i--) {
            name.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return name.toString();
    }

    private static String randomApprovalId() {
        byte[] bytes = new byte[6];
        ThreadLocalRandom.current().nextBytes(bytes);
        return "apr-" + HexFormat.of().formatHex(bytes);
    }

    /**
     * A {@code COMPLETED} report with one Evidence_Entry per {@code known} id whose timeline entries,
     * hypotheses (supporting and contradicting) and actions cite a random mix of {@code known} and
     * {@code unknown} ids; every citation is appended to {@code cited}. The first timeline entry cites one id
     * of each kind. Stored directly, so the ReportValidator (which rejects unknown ids) is not involved.
     */
    private static InvestigationReport citingReport(String id, SplittableRandom random, List<String> known,
            List<String> unknown, List<String> cited) {
        List<EvidenceRef> evidence = new ArrayList<>();
        for (int i = 0; i < known.size(); i++) {
            evidence.add(new EvidenceRef(known.get(i), EvidenceType.ALERT, "alert-intake", T0.plusSeconds(i + 1),
                    "finding " + (i + 1), "citation " + (i + 1)));
        }
        List<TimelineEntry> timeline = new ArrayList<>();
        int entries = 1 + random.nextInt(3);
        for (int i = 0; i < entries; i++) {
            List<String> ids = new ArrayList<>();
            if (i == 0) {
                ids.add(pick(random, known.toArray(String[]::new)));
                ids.add(pick(random, unknown.toArray(String[]::new)));
            }
            ids.addAll(cites(random, known, unknown, i == 0 ? 0 : 1));
            cited.addAll(ids);
            timeline.add(new TimelineEntry(T0.plusSeconds(i), "event " + i, ids));
        }
        List<Hypothesis> hypotheses = new ArrayList<>();
        for (int i = 1, n = 1 + random.nextInt(3); i <= n; i++) {
            List<String> supporting = cites(random, known, unknown, 0);
            List<String> contradicting = cites(random, known, unknown, 0);
            cited.addAll(supporting);
            cited.addAll(contradicting);
            hypotheses.add(new Hypothesis("hypothesis " + i, pick(random, Confidence.values()), supporting,
                    contradicting, random.nextBoolean()));
        }
        List<RecommendedAction> actions = new ArrayList<>();
        for (int i = 1, n = 1 + random.nextInt(3); i <= n; i++) {
            List<String> ids = cites(random, known, unknown, 1);
            cited.addAll(ids);
            actions.add(new RecommendedAction(i, ActionType.INVESTIGATE, "check " + i, false, ids));
        }
        return new InvestigationReport(id, InvestigationStatus.COMPLETED, "summary of " + id,
                new IncidentTimeline(timeline), hypotheses, actions, List.of(), evidence, T0.plusSeconds(60));
    }

    /** {@code min} to {@code min + 3} ids, each drawn from {@code known} or {@code unknown} at random. */
    private static List<String> cites(SplittableRandom random, List<String> known, List<String> unknown, int min) {
        List<String> ids = new ArrayList<>();
        for (int k = min + random.nextInt(4); k > 0; k--) {
            List<String> from = random.nextBoolean() ? known : unknown;
            ids.add(from.get(random.nextInt(from.size())));
        }
        return ids;
    }

    // ---- shared helpers for the Report_Page properties ----

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

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

    /** A report with 1 to 3 Evidence_Entries; see {@link #report(String, SplittableRandom, int)}. */
    private static InvestigationReport report(String id, SplittableRandom random) {
        return report(id, random, 1 + random.nextInt(3));
    }

    /**
     * A report with {@code count} Evidence_Entries, one hypothesis (random Confidence and establishedCause)
     * and one action per entry citing it, and a timeline entry citing all of them.
     */
    private static InvestigationReport report(String id, SplittableRandom random, int count) {
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
