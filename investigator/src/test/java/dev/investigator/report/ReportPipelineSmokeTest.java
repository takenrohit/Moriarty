package dev.investigator.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.investigator.domain.ActionType;
import dev.investigator.domain.Confidence;
import dev.investigator.domain.DraftAction;
import dev.investigator.domain.DraftTimelineEntry;
import dev.investigator.domain.Evidence;
import dev.investigator.domain.EvidenceRef;
import dev.investigator.domain.EvidenceType;
import dev.investigator.domain.InvestigationReport;
import dev.investigator.domain.InvestigationStatus;
import dev.investigator.domain.RecommendedAction;
import dev.investigator.domain.ReportDraft;
import dev.investigator.domain.TimelineEntry;
import dev.investigator.investigation.InvestigationContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Example tests for the fallback builder, assembler and draft parser (Req 14.6, 14.11, 15.3, 15.10, 16.4). */
class ReportPipelineSmokeTest {

    private static final String INC = "inc-0123456789ab";
    private static final Instant T0 = Instant.parse("2026-11-23T14:40:00Z");
    private static final Instant NOW = Instant.parse("2026-11-23T14:45:00Z");

    private final FallbackReportBuilder fallback = new FallbackReportBuilder();
    private final ReportAssembler assembler = new ReportAssembler();
    private final ReportDraftParser parser = new ReportDraftParser();

    @Test
    void fallbackOrdersTimelineByTimeThenEvidenceNumberAndIgnoresOtherIncidents() {
        List<Evidence> evidence = List.of(
                evidence(INC, 10, T0.minusSeconds(60), "ten"),
                evidence(INC, 2, T0.minusSeconds(60), "two"),
                evidence(INC, 1, T0, "alert"),
                evidence("inc-ffffffffffff", 3, T0.minusSeconds(600), "other"));

        InvestigationReport report = fallback.build(INC, "checkout-api", InvestigationStatus.INCONCLUSIVE,
                "timed out after 3m", List.of(), evidence, NOW);

        assertThat(report.timeline().entries()).extracting(TimelineEntry::description)
                .containsExactly("two", "ten", "alert");
        assertThat(report.evidence()).extracting(EvidenceRef::id)
                .containsExactly(id(1), id(2), id(10));
        assertThat(report.summary()).isEqualTo("Investigation inconclusive: timed out after 3m. Collected 3 evidence"
                + " items; no validated analysis is available.");
        assertThat(report.hypotheses()).isEmpty();
        assertThat(report.actions()).extracting(RecommendedAction::type)
                .containsExactly(ActionType.INVESTIGATE, ActionType.ESCALATE);
        assertThat(report.actions().get(1).description()).isEqualTo("Escalate to the on-call owner of checkout-api");
        assertThat(report.actions()).allSatisfy(a -> {
            assertThat(a.evidenceIds()).containsExactly(id(1), id(2), id(10));
            assertThat(a.requiresApproval()).isFalse();
        });
        assertThat(report.openQuestions()).containsExactly("timed out after 3m");
        assertThat(report.generatedAt()).isEqualTo(NOW);
    }

    @Test
    void fallbackCapsOpenQuestionsAtTenAndEachAt300CodePoints() {
        List<String> details = IntStream.range(0, 12).mapToObj(i -> "error " + i + " " + "x".repeat(400)).toList();

        InvestigationReport report = fallback.build(INC, null, InvestigationStatus.FAILED, "LLM call failed: X",
                details, List.of(), NOW);

        assertThat(report.openQuestions()).hasSize(FallbackReportBuilder.MAX_OPEN_QUESTIONS);
        assertThat(report.openQuestions().get(0)).isEqualTo("LLM call failed: X");
        assertThat(report.openQuestions().get(9)).isEqualTo("4 more errors not shown");
        assertThat(report.openQuestions()).allSatisfy(q -> assertThat(q.codePointCount(0, q.length()))
                .isLessThanOrEqualTo(FallbackReportBuilder.MAX_OPEN_QUESTION_LENGTH));
        assertThat(report.summary()).startsWith("Investigation failed: LLM call failed: X. Collected 0 evidence items");
        assertThat(report.actions().get(1).description()).endsWith(FallbackReportBuilder.UNKNOWN_SERVICE);
    }

    @Test
    void fallbackRejectsNonFallbackStatuses() {
        assertThatThrownBy(() -> fallback.build(INC, "s", InvestigationStatus.COMPLETED, "r", List.of(), List.of(), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void assemblerSortsActionsConvertsTimesAndAppendsOpenQuestions() {
        ReportDraft draft = new ReportDraft("inc-other", "summary",
                List.of(new DraftTimelineEntry("2026-11-23T14:38:00Z", "errors start", List.of(id(1)))),
                List.of(),
                List.of(new DraftAction(2, ActionType.ROLLBACK, "roll back", true, List.of(id(1))),
                        new DraftAction(1, ActionType.INVESTIGATE, "look", false, List.of(id(1)))),
                List.of("q1"));

        InvestigationReport report = assembler.assemble(draft, context(), InvestigationStatus.INCONCLUSIVE,
                List.of("budget"), List.of(), NOW);

        assertThat(report.incidentId()).isEqualTo(INC);
        assertThat(report.status()).isEqualTo(InvestigationStatus.INCONCLUSIVE);
        assertThat(report.timeline().entries()).extracting(TimelineEntry::at)
                .containsExactly(Instant.parse("2026-11-23T14:38:00Z"));
        assertThat(report.actions()).extracting(RecommendedAction::order).containsExactly(1, 2);
        assertThat(report.openQuestions()).containsExactly("q1", "budget");
    }

    @Test
    void assemblerAppliesTheActionPolicy() {
        ReportDraft draft = new ReportDraft(INC, "summary", List.of(), List.of(),
                List.of(new DraftAction(1, ActionType.ROLLBACK, "roll back", false, List.of(id(1))),
                        new DraftAction(2, ActionType.ESCALATE, "page", true, List.of(id(1)))),
                List.of());

        InvestigationReport report = assembler.assemble(draft, context(), InvestigationStatus.COMPLETED, List.of(),
                List.of(), NOW);

        assertThat(report.actions()).extracting(RecommendedAction::requiresApproval).containsExactly(true, false);
    }

    @Test
    void parserStripsAJsonFenceAndChecksTimelineTimes() {
        String timeline = "{\"incidentId\":\"%s\",\"summary\":\"s\",\"timeline\":[{\"at\":\"%s\",\"description\":\"d\","
                + "\"evidenceIds\":[\"%s\"]}],\"hypotheses\":[],\"actions\":[],\"openQuestions\":[]}";

        assertThat(parser.parse("```json\n" + timeline.formatted(INC, "2026-11-23T14:38:00Z", id(1)) + "\n```"))
                .isInstanceOf(ParseResult.Ok.class);
        assertThat(errors(timeline.formatted(INC, "2026-11-23 14:38", id(1)))).singleElement()
                .satisfies(e -> assertThat(e).startsWith("$.timeline[0].at: "));
    }

    @Test
    void assemblerRejectsTimelineTimeThatIsNotAnInstantNamingThePath() {
        ReportDraft draft = new ReportDraft(INC, "s",
                List.of(new DraftTimelineEntry("2026-11-23 14:38", "d", List.of(id(1)))), List.of(), List.of(),
                List.of());

        assertThatThrownBy(() -> assembler.assemble(draft, context(), InvestigationStatus.COMPLETED, List.of(),
                List.of(), NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("$.timeline[0].at");
    }

    @Test
    void parserAcceptsAStrictDraft() {
        String json = """
                {"incidentId":"%s","summary":"s","timeline":[],"hypotheses":[{"statement":"h","confidence":"HIGH",
                "supportingEvidenceIds":["%s"],"contradictingEvidenceIds":[],"establishedCause":true}],
                "actions":[],"openQuestions":[]}
                """.formatted(INC, id(1));

        assertThat(parser.parse(json)).isInstanceOfSatisfying(ParseResult.Ok.class,
                ok -> assertThat(ok.draft().hypotheses().get(0).confidence()).isEqualTo(Confidence.HIGH));
    }

    @Test
    void parserRejectsUnknownFieldsMissingFieldsCoercionAndTrailingContent() {
        String base = "{\"incidentId\":\"%s\",\"summary\":\"s\",\"timeline\":[],\"hypotheses\":[],\"actions\":[],"
                .formatted(INC);

        assertThat(errors(base + "\"openQuestions\":[],\"extra\":1}")).singleElement()
                .satisfies(e -> assertThat(e).startsWith("$.extra"));
        assertThat(errors(base + "\"openQuestions\":[]} {}")).hasSize(1);
        assertThat(errors(base.replace("\"actions\":[],", "") + "\"openQuestions\":[]}")).hasSize(1);
        assertThat(errors(base.replace("\"actions\":[]", "\"actions\":[{\"order\":1,\"type\":\"INVESTIGATE\","
                + "\"description\":\"d\",\"requiresApproval\":\"true\",\"evidenceIds\":[]}]")
                + "\"openQuestions\":[]}")).hasSize(1);
        assertThat(errors("  ")).containsExactly(ReportDraftParser.EMPTY_DRAFT);
    }

    private List<String> errors(String raw) {
        ParseResult result = parser.parse(raw);
        assertThat(result).isInstanceOf(ParseResult.Invalid.class);
        return ((ParseResult.Invalid) result).errors();
    }

    private static InvestigationContext context() {
        return new InvestigationContext(INC, T0.minusSeconds(3600), T0.plusSeconds(600), NOW.plusSeconds(180),
                "0".repeat(31) + "1", id(1), T0, 15);
    }

    private static Evidence evidence(String incidentId, int n, Instant observedAt, String headline) {
        return new Evidence("ev-" + incidentId + "-" + n, incidentId, EvidenceType.METRIC, "metrics", observedAt,
                "{}", headline, Map.of(), "citation", Set.of());
    }

    private static String id(int n) {
        return "ev-" + INC + "-" + n;
    }
}
