package dev.investigator.report;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.investigator.domain.ActionType;
import dev.investigator.domain.Confidence;
import dev.investigator.domain.DraftAction;
import dev.investigator.domain.DraftHypothesis;
import dev.investigator.domain.DraftTimelineEntry;
import dev.investigator.domain.Hypothesis;
import dev.investigator.domain.IncidentTimeline;
import dev.investigator.domain.InvestigationReport;
import dev.investigator.domain.InvestigationStatus;
import dev.investigator.domain.RecommendedAction;
import dev.investigator.domain.ReportDraft;
import dev.investigator.domain.TimelineEntry;
import dev.investigator.investigation.InvestigationContext;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

/**
 * NFR-40 unit tests for the Report_Draft_Parser, Report_Validator and Action_Policy (Req 15.2, 15.3,
 * 15.6 to 15.8, 15.11, 15.12, 16.5, 19.1).
 */
class ReportValidatorTest {

    private static final String INC = "inc-4f2a9c1e7b3d";
    private static final String OTHER = "inc-ffffffffffff";
    private static final Instant DETECTED = Instant.parse("2026-11-23T14:40:00Z");
    private static final Instant NOW = Instant.parse("2026-11-23T14:43:00Z");
    /** Evidence stored for {@link #INC}: ev-1 to ev-50, enough for every draft below. */
    private static final Set<String> KNOWN =
            IntStream.rangeClosed(1, 50).mapToObj(ReportValidatorTest::id).collect(Collectors.toUnmodifiableSet());

    private final ReportDraftParser parser = new ReportDraftParser();
    private final ReportDraftPrinter printer = new ReportDraftPrinter();
    private final ReportValidator validator = new ReportValidator();
    private final ReportAssembler assembler = new ReportAssembler();

    /**
     * The expected demo report of {@code demo-scenario.md} as a model draft. Demo evidence ev-1 to ev-7
     * keep their numbers; ev-8 is the ALERT evidence cited by "HighErrorRate alert fires". IST times are
     * written in UTC (20:10 IST = 14:40Z).
     */
    private static final String DEMO_DRAFT = """
            {
              "incidentId": "{inc}",
              "summary": "The checkout API error rate increased from 0.2% to 12% beginning at 20:08 IST. Logs and traces consistently show timeouts calling the payment gateway. checkout-api v2.14.3 was deployed 12 minutes before errors began; the deployment is correlated, but the evidence points to payment-gateway timeouts.",
              "timeline": [
                {"at": "2026-11-23T14:26:00Z", "description": "checkout-api v2.14.3 deployed", "evidenceIds": ["ev-{inc}-2"]},
                {"at": "2026-11-23T14:37:00Z", "description": "payment-gateway p99 latency rises to 5 s", "evidenceIds": ["ev-{inc}-4"]},
                {"at": "2026-11-23T14:38:00Z", "description": "checkout-api 5xx rate starts rising; PaymentGatewayTimeoutException appears", "evidenceIds": ["ev-{inc}-1", "ev-{inc}-3"]},
                {"at": "2026-11-23T14:40:00Z", "description": "HighErrorRate alert fires", "evidenceIds": ["ev-{inc}-8"]}
              ],
              "hypotheses": [
                {
                  "statement": "Payment-gateway timeouts caused by slow responses from payment-provider",
                  "confidence": "HIGH",
                  "supportingEvidenceIds": ["ev-{inc}-4", "ev-{inc}-7", "ev-{inc}-3"],
                  "contradictingEvidenceIds": [],
                  "establishedCause": true
                },
                {
                  "statement": "checkout-api v2.14.3 deployment caused the error rate increase",
                  "confidence": "LOW",
                  "supportingEvidenceIds": ["ev-{inc}-2"],
                  "contradictingEvidenceIds": ["ev-{inc}-5", "ev-{inc}-7"],
                  "establishedCause": false
                }
              ],
              "actions": [
                {"order": 1, "type": "INVESTIGATE", "description": "Check payment-gateway health and upstream provider status.", "requiresApproval": false, "evidenceIds": ["ev-{inc}-4", "ev-{inc}-7"]},
                {"order": 2, "type": "INVESTIGATE", "description": "Review checkout-api v2.14.3 changes for timeout or retry modifications.", "requiresApproval": false, "evidenceIds": ["ev-{inc}-2"]},
                {"order": 3, "type": "INVESTIGATE", "description": "If gateway remains unhealthy, follow the payment-gateway degradation runbook.", "requiresApproval": false, "evidenceIds": ["ev-{inc}-6"]},
                {"order": 4, "type": "ROLLBACK", "description": "Rollback of checkout-api is not recommended on current evidence.", "requiresApproval": true, "evidenceIds": ["ev-{inc}-2", "ev-{inc}-5"]},
                {"order": 5, "type": "RESTART", "description": "Do not restart services without on-call approval.", "requiresApproval": true, "evidenceIds": []}
              ],
              "openQuestions": ["Is payment-provider reporting an outage?"]
            }
            """.replace("{inc}", INC);

    // --- demo report -------------------------------------------------------------------------------

    @Test
    void expectedDemoReportPassesParserValidatorAndFinalCheck() {
        ReportDraft draft = parseOk(DEMO_DRAFT);

        assertThat(validator.validateDraft(draft, INC, KNOWN).violations()).isEmpty();

        InvestigationReport report = assembler.assemble(draft, context(), InvestigationStatus.COMPLETED, List.of(),
                List.of(), NOW);
        assertThat(validator.validateReport(report, KNOWN).violations()).isEmpty();
        assertThat(report.actions())
                .filteredOn(a -> a.type() == ActionType.ROLLBACK || a.type() == ActionType.RESTART)
                .hasSize(2)
                .allSatisfy(a -> assertThat(a.requiresApproval()).isTrue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"%s", "```json\n%s\n```", "```\n%s\n```", "  \n```JSON\n%s```\n  "})
    void fencedAndUnfencedDraftsParseToTheSameDraft(String wrapper) {
        ReportDraft unfenced = parseOk(DEMO_DRAFT);

        assertThat(parseOk(wrapper.formatted(DEMO_DRAFT))).isEqualTo(unfenced);
    }

    @Test
    void textAroundAFenceIsNotStripped() {
        assertThat(parser.parse("Here is the report:\n```json\n" + DEMO_DRAFT + "\n```"))
                .isInstanceOf(ParseResult.Invalid.class);
    }

    // --- single semantic violations ----------------------------------------------------------------

    @Test
    void incidentIdMismatchIsRejected() {
        ReportDraft draft = draft(OTHER, List.of(timeline(id(1))), List.of(hypothesis(List.of(id(1)), List.of())),
                List.of(action(1, id(1))));

        assertThat(validator.validateDraft(draft, INC, KNOWN).violations())
                .containsExactly("$.incidentId: must be the investigation's incident ID " + INC);
    }

    @Test
    void timelineEntryWithoutEvidenceIdsIsRejected() {
        ReportDraft draft = draft(INC, List.of(timeline(id(1)), timeline()),
                List.of(hypothesis(List.of(id(1)), List.of())), List.of());

        assertThat(validator.validateDraft(draft, INC, KNOWN).violations())
                .containsExactly("$.timeline[1].evidenceIds: " + ReportValidator.TIMELINE_UNCITED);
    }

    @Test
    void hypothesisWithoutSupportingEvidenceIdsIsRejected() {
        ReportDraft draft = draft(INC, List.of(timeline(id(1))), List.of(hypothesis(List.of(), List.of(id(2)))),
                List.of());

        assertThat(validator.validateDraft(draft, INC, KNOWN).violations())
                .containsExactly("$.hypotheses[0].supportingEvidenceIds: " + ReportValidator.HYPOTHESIS_UNCITED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ev-" + OTHER + "-3", "ev-" + INC + "-0", "ev-" + INC + "-03", "ev-" + INC + "-3x", "ev-3"})
    void evidenceIdOfAnotherIncidentOrMalformedIsRejectedWithoutEchoingIt(String cited) {
        ReportDraft draft = draft(INC, List.of(timeline(id(1))), List.of(hypothesis(List.of(id(1), cited), List.of())),
                List.of());

        assertThat(validator.validateDraft(draft, INC, KNOWN).violations()).singleElement()
                .isEqualTo("$.hypotheses[0].supportingEvidenceIds[1]: is not an evidence ID of incident " + INC
                        + " (expected ev-" + INC + "-<n>)")
                .satisfies(v -> assertThat(v).doesNotContain(cited));
    }

    @Test
    void wellFormedEvidenceIdThatWasNeverStoredIsRejectedAtItsPath() {
        ReportDraft draft = draft(INC, List.of(timeline(id(1), id(51))),
                List.of(hypothesis(List.of(id(1)), List.of(id(99)))), List.of(action(1, id(2), id(77))));

        assertThat(validator.validateDraft(draft, INC, KNOWN).violations()).containsExactlyInAnyOrder(
                "$.timeline[0].evidenceIds[1]: " + ReportValidator.UNKNOWN_EVIDENCE,
                "$.hypotheses[0].contradictingEvidenceIds[0]: " + ReportValidator.UNKNOWN_EVIDENCE,
                "$.actions[0].evidenceIds[1]: " + ReportValidator.UNKNOWN_EVIDENCE);
    }

    @Test
    void foreignIdAbsentFromTheStoreGetsOnlyTheFormatViolation() {
        ReportDraft draft = draft(INC, List.of(timeline("ev-" + OTHER + "-1")), List.of(), List.of());

        assertThat(validator.validateDraft(draft, INC, KNOWN).violations()).containsExactly(
                "$.timeline[0].evidenceIds[0]: is not an evidence ID of incident " + INC
                        + " (expected ev-" + INC + "-<n>)");
    }

    @Test
    void finalReportCitingEvidenceThatWasNeverStoredIsRejected() {
        InvestigationReport report = new InvestigationReport(INC, InvestigationStatus.COMPLETED, "s",
                new IncidentTimeline(List.of(new TimelineEntry(DETECTED, "d", List.of(id(1))))), List.of(),
                List.of(new RecommendedAction(1, ActionType.INVESTIGATE, "look", false, List.of(id(1)))),
                List.of(), List.of(), NOW);

        assertThat(validator.validateReport(report, KNOWN).valid()).isTrue();
        assertThat(validator.validateReport(report, Set.of(id(2))).violations()).containsExactlyInAnyOrder(
                "$.timeline.entries[0].evidenceIds[0]: " + ReportValidator.UNKNOWN_EVIDENCE,
                "$.actions[0].evidenceIds[0]: " + ReportValidator.UNKNOWN_EVIDENCE);
    }

    @Test
    void anActionMayCiteNoEvidence() {
        ReportDraft draft = draft(INC, List.of(timeline(id(1))), List.of(), List.of(action(1)));

        assertThat(validator.validateDraft(draft, INC, KNOWN).valid()).isTrue();
    }

    // --- every violation reported -------------------------------------------------------------------

    @Test
    void everyDraftViolationIsReported() {
        ReportDraft draft = draft(OTHER, List.of(timeline(id(1)), timeline()),
                List.of(hypothesis(List.of(), List.of("ev-" + OTHER + "-1"))),
                List.of(action(1, id(1), "ev-" + INC + "-0")));

        String wrongId = ": is not an evidence ID of incident " + INC + " (expected ev-" + INC + "-<n>)";
        assertThat(validator.validateDraft(draft, INC, KNOWN).violations()).containsExactlyInAnyOrder(
                "$.incidentId: must be the investigation's incident ID " + INC,
                "$.timeline[1].evidenceIds: " + ReportValidator.TIMELINE_UNCITED,
                "$.hypotheses[0].supportingEvidenceIds: " + ReportValidator.HYPOTHESIS_UNCITED,
                "$.hypotheses[0].contradictingEvidenceIds[0]" + wrongId,
                "$.actions[0].evidenceIds[1]" + wrongId);
    }

    @Test
    void everyFinalReportViolationIsReported() {
        InvestigationReport report = new InvestigationReport(INC, InvestigationStatus.RUNNING, " ",
                new IncidentTimeline(List.of(new TimelineEntry(null, "d", List.of()))),
                List.of(new Hypothesis("h", null, List.of("ev-" + OTHER + "-1"), List.of(), false)),
                List.of(new RecommendedAction(1, ActionType.ROLLBACK, "roll back", false, List.of(id(1)))),
                Collections.nCopies(ReportValidator.MAX_REPORT_OPEN_QUESTIONS + 1, "q"), List.of(), null);

        assertThat(validator.validateReport(report, KNOWN).violations()).containsExactlyInAnyOrder(
                "$.status: must be COMPLETED, INCONCLUSIVE or FAILED",
                "$.summary: is required",
                "$.generatedAt: is required",
                "$.timeline.entries[0].at: is required",
                "$.timeline.entries[0].evidenceIds: " + ReportValidator.TIMELINE_UNCITED,
                "$.hypotheses[0].confidence: is required",
                "$.hypotheses[0].supportingEvidenceIds[0]: is not an evidence ID of incident " + INC
                        + " (expected ev-" + INC + "-<n>)",
                "$.actions[0].requiresApproval: must be true for ROLLBACK actions",
                "$.openQuestions: must have at most " + ReportValidator.MAX_REPORT_OPEN_QUESTIONS + " items");
    }

    @Test
    void finalReportAllowsElevenOpenQuestions() {
        InvestigationReport report = new InvestigationReport(INC, InvestigationStatus.INCONCLUSIVE, "s",
                new IncidentTimeline(List.of(new TimelineEntry(DETECTED, "d", List.of(id(1))))), List.of(), List.of(),
                Collections.nCopies(ReportValidator.MAX_REPORT_OPEN_QUESTIONS, "q"), List.of(), NOW);

        assertThat(validator.validateReport(report, KNOWN).valid()).isTrue();
    }

    // --- schema checks in the parser ----------------------------------------------------------------

    @Test
    void unknownFieldIsRejectedNamingItsPath() {
        String json = DEMO_DRAFT.replaceFirst("\"establishedCause\": true", "\"establishedCause\": true, \"extra\": 1");

        assertThat(parseErrors(json)).containsExactly(
                "$.hypotheses[0].extra: is not defined in the schema (additional properties are not allowed)");
    }

    @ParameterizedTest(name = "{0} limit {1}")
    @CsvSource({"timeline, 50", "hypotheses, 5", "actions, 10", "openQuestions, 10"})
    void listLimitsAcceptNAndRejectNPlusOne(String field, int limit) {
        ReportDraft atLimit = draftWithListSize(field, limit);
        assertThat(validator.validateDraft(parseOk(printer.print(atLimit)), INC, KNOWN).valid()).isTrue();

        assertThat(parseErrors(printer.print(draftWithListSize(field, limit + 1)))).singleElement()
                .satisfies(e -> assertThat(e).startsWith("$." + field + ": ").contains(String.valueOf(limit)));
    }

    @Test
    void summaryOf2000CharactersIsAcceptedAnd2001Rejected() {
        assertThat(parseOk(printer.print(draftWithSummary("x".repeat(2000)))).summary()).hasSize(2000);

        assertThat(parseErrors(printer.print(draftWithSummary("x".repeat(2001)))))
                .containsExactly("$.summary: must be at most 2,000 characters long");
    }

    // --- action policy ------------------------------------------------------------------------------

    @Test
    void actionPolicyLogsAWarningWithIncidentIdAndOrderOnlyForReplacedValues() {
        Logger logger = (Logger) LoggerFactory.getLogger(ActionPolicy.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            List<RecommendedAction> applied = new ActionPolicy().apply(INC, List.of(
                    new RecommendedAction(1, ActionType.INVESTIGATE, "look", false, List.of(id(1))),
                    new RecommendedAction(2, ActionType.ROLLBACK, "roll back", false, List.of(id(2))),
                    new RecommendedAction(3, ActionType.ESCALATE, "page", true, List.of())));

            assertThat(applied).extracting(RecommendedAction::requiresApproval).containsExactly(false, true, false);
            assertThat(appender.list).hasSize(2).allSatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.WARN));
            assertThat(appender.list.get(0).getFormattedMessage())
                    .contains("incident_id=" + INC, "action_order=2", "type=ROLLBACK", "draft=false", "policy=true");
            assertThat(appender.list.get(1).getFormattedMessage())
                    .contains("incident_id=" + INC, "action_order=3", "type=ESCALATE", "draft=true", "policy=false");
        } finally {
            logger.detachAppender(appender);
        }
    }

    // --- helpers ------------------------------------------------------------------------------------

    private ReportDraft parseOk(String raw) {
        ParseResult result = parser.parse(raw);
        assertThat(result).as("parse result").isInstanceOf(ParseResult.Ok.class);
        return ((ParseResult.Ok) result).draft();
    }

    private List<String> parseErrors(String raw) {
        ParseResult result = parser.parse(raw);
        assertThat(result).as("parse result").isInstanceOf(ParseResult.Invalid.class);
        return ((ParseResult.Invalid) result).errors();
    }

    private static ReportDraft draft(String incidentId, List<DraftTimelineEntry> timeline,
            List<DraftHypothesis> hypotheses, List<DraftAction> actions) {
        return new ReportDraft(incidentId, "summary", timeline, hypotheses, actions, List.of());
    }

    private static ReportDraft draftWithSummary(String summary) {
        return new ReportDraft(INC, summary, List.of(timeline(id(1))), List.of(), List.of(), List.of());
    }

    private static ReportDraft draftWithListSize(String field, int size) {
        int timeline = field.equals("timeline") ? size : 1;
        int hypotheses = field.equals("hypotheses") ? size : 1;
        int actions = field.equals("actions") ? size : 1;
        int questions = field.equals("openQuestions") ? size : 1;
        return new ReportDraft(INC, "summary",
                IntStream.range(0, timeline).mapToObj(i -> timeline(id(i + 1))).toList(),
                IntStream.range(0, hypotheses).mapToObj(i -> hypothesis(List.of(id(i + 1)), List.of())).toList(),
                IntStream.range(0, actions).mapToObj(i -> action(i + 1, id(i + 1))).toList(),
                IntStream.range(0, questions).mapToObj(i -> "question " + i).toList());
    }

    private static DraftTimelineEntry timeline(String... evidenceIds) {
        return new DraftTimelineEntry("2026-11-23T14:38:00Z", "errors start", List.of(evidenceIds));
    }

    private static DraftHypothesis hypothesis(List<String> supporting, List<String> contradicting) {
        return new DraftHypothesis("gateway timeouts", Confidence.MEDIUM, supporting, contradicting, false);
    }

    private static DraftAction action(int order, String... evidenceIds) {
        return new DraftAction(order, ActionType.INVESTIGATE, "check the gateway", false, List.of(evidenceIds));
    }

    private static InvestigationContext context() {
        return new InvestigationContext(INC, DETECTED.minusSeconds(3600), DETECTED.plusSeconds(600),
                NOW.plusSeconds(180), "0".repeat(31) + "1", id(8), DETECTED, 15);
    }

    private static String id(int n) {
        return "ev-" + INC + "-" + n;
    }
}
