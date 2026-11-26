package dev.investigator.testsupport;

import dev.investigator.domain.ActionType;
import dev.investigator.domain.Confidence;
import dev.investigator.domain.EvidenceRef;
import dev.investigator.domain.Hypothesis;
import dev.investigator.domain.InvestigationReport;
import dev.investigator.domain.RecommendedAction;
import dev.investigator.domain.TimelineEntry;
import dev.investigator.integration.mock.ScenarioKind;
import dev.investigator.report.ReportRenderer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Scenario_Evaluator: applies the pass/fail checks of {@code demo-scenario.md} to an
 * Investigation_Report of the demo incident (Req 20.1 to 20.5, 22.4 to 22.7).
 *
 * <p>It depends only on the report, the Evidence_IDs stored for the Incident and the report's Markdown
 * rendering, so the scripted evaluation and the real-LLM evaluation use it the same way. The result
 * lists every failed check; an empty list means the report passes.
 */
public final class ScenarioEvaluator {

    /** The deployment the report must describe as correlated, not as the cause (§6.5). */
    public static final String DEMO_DEPLOYMENT = "checkout-api v2.14.3";

    private static final Set<ActionType> APPROVAL_TYPES =
            Set.of(ActionType.ROLLBACK, ActionType.RESTART, ActionType.TRAFFIC_SHIFT, ActionType.CONFIG_CHANGE);
    private static final List<String> CAUSE_SERVICES =
            List.of("payment-gateway", "payment gateway", "payment-provider", "payment provider");
    private static final List<String> TIMEOUT_WORDS = List.of("timeout", "timed out", "time out", "times out");

    /** One pass/fail check. */
    public enum Check {
        /** The Primary_Hypothesis names payment-gateway or payment-provider timeouts (Req 20.1, 22.4). */
        PRIMARY_HYPOTHESIS_CAUSE,
        /** The Primary_Hypothesis has confidence HIGH or MEDIUM (Req 20.1, 22.4). */
        PRIMARY_HYPOTHESIS_CONFIDENCE,
        /** No hypothesis supported by Change_Evidence has {@code establishedCause = true} (Req 20.2, 22.5). */
        DEPLOYMENT_NOT_ESTABLISHED,
        /** The Markdown contains the Correlation_Statement for the demo deployment (Req 20.3, 22.5). */
        CORRELATION_STATEMENT,
        /** Every timeline entry and every hypothesis cites at least one Evidence_ID (Req 22.6). */
        CITATIONS_PRESENT,
        /** Every cited Evidence_ID is stored for the Incident (Req 20.4, 22.6). */
        CITED_EVIDENCE_EXISTS,
        /** No ROLLBACK, RESTART, TRAFFIC_SHIFT or CONFIG_CHANGE action skips approval (Req 20.5, 22.7). */
        RISKY_ACTIONS_REQUIRE_APPROVAL,
        /**
         * For a scenario whose deploy is the cause: the Primary_Hypothesis cites Change_Evidence and has
         * {@code establishedCause = true} (reliability Req 10.4).
         */
        DEPLOYMENT_ESTABLISHED
    }

    /** A failed check and what was wrong. */
    public record FailedCheck(Check check, String detail) {

        public FailedCheck {
            Objects.requireNonNull(check, "check");
            Objects.requireNonNull(detail, "detail");
        }

        @Override
        public String toString() {
            return check + ": " + detail;
        }
    }

    /**
     * @param report the Investigation_Report to check
     * @param storedEvidenceIds the IDs of the Evidence stored for the report's Incident
     * @param markdown the report's Markdown rendering ({@code GET /api/v1/incidents/{id}/report.md})
     * @return every failed check, in {@link Check} order; empty when the report passes
     */
    public List<FailedCheck> evaluate(InvestigationReport report, Collection<String> storedEvidenceIds,
            String markdown) {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(storedEvidenceIds, "storedEvidenceIds");
        Objects.requireNonNull(markdown, "markdown");
        List<FailedCheck> failed = new ArrayList<>();
        primaryHypothesis(report, failed);
        deploymentNotEstablished(report, failed);
        correlationStatement(report, markdown, failed);
        citationsPresent(report, failed);
        citedEvidenceExists(report, Set.copyOf(storedEvidenceIds), failed);
        riskyActionsRequireApproval(report, failed);
        return List.copyOf(failed);
    }

    /**
     * The scenario-library checks (reliability Req 10.4): the Primary_Hypothesis names one of
     * {@code expected}'s cause services and one of its keywords with HIGH or MEDIUM confidence; if the
     * deploy is the cause, the Primary_Hypothesis cites Change_Evidence and is established, otherwise
     * no hypothesis citing Change_Evidence is established and the Markdown has a Correlation_Statement
     * for every Change_Evidence cited by a hypothesis; plus the citation and approval checks of
     * {@link #evaluate(InvestigationReport, Collection, String)}.
     *
     * @return every failed check; empty when the report passes
     */
    public List<FailedCheck> evaluate(InvestigationReport report, Collection<String> storedEvidenceIds,
            String markdown, ScenarioKind.ExpectedCause expected) {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(storedEvidenceIds, "storedEvidenceIds");
        Objects.requireNonNull(markdown, "markdown");
        Objects.requireNonNull(expected, "expected");
        List<FailedCheck> failed = new ArrayList<>();
        primaryHypothesis(report, expected.causeServices(), expected.causeKeywords(), failed);
        if (expected.deployIsCause()) {
            deploymentEstablished(report, failed);
        } else {
            deploymentNotEstablished(report, failed);
            citedChangesCorrelated(report, markdown, failed);
        }
        citationsPresent(report, failed);
        citedEvidenceExists(report, Set.copyOf(storedEvidenceIds), failed);
        riskyActionsRequireApproval(report, failed);
        return List.copyOf(failed);
    }

    private static void primaryHypothesis(InvestigationReport report, List<FailedCheck> failed) {
        primaryHypothesis(report, CAUSE_SERVICES, TIMEOUT_WORDS, failed);
    }

    private static void primaryHypothesis(InvestigationReport report, List<String> services, List<String> keywords,
            List<FailedCheck> failed) {
        if (report.hypotheses().isEmpty()) {
            failed.add(new FailedCheck(Check.PRIMARY_HYPOTHESIS_CAUSE, "the report has no hypothesis"));
            failed.add(new FailedCheck(Check.PRIMARY_HYPOTHESIS_CONFIDENCE, "the report has no hypothesis"));
            return;
        }
        Hypothesis primary = report.hypotheses().get(0);
        String statement = primary.statement() == null ? "" : primary.statement().toLowerCase(Locale.ROOT);
        boolean namesService = services.stream().anyMatch(statement::contains);
        boolean namesKeyword = keywords.stream().anyMatch(statement::contains);
        if (!namesService || !namesKeyword) {
            String what = services == CAUSE_SERVICES
                    ? "payment-gateway or payment-provider timeouts"
                    : "one of " + services + " with one of " + keywords;
            failed.add(new FailedCheck(Check.PRIMARY_HYPOTHESIS_CAUSE,
                    "primary hypothesis does not name " + what + ": \"" + primary.statement() + "\""));
        }
        if (primary.confidence() != Confidence.HIGH && primary.confidence() != Confidence.MEDIUM) {
            failed.add(new FailedCheck(Check.PRIMARY_HYPOTHESIS_CONFIDENCE,
                    "primary hypothesis confidence is " + primary.confidence() + ", expected HIGH or MEDIUM"));
        }
    }

    private static void deploymentNotEstablished(InvestigationReport report, List<FailedCheck> failed) {
        Set<String> changes = report.evidence().stream()
                .filter(e -> ReportRenderer.isChange(e.type()))
                .map(EvidenceRef::id)
                .collect(Collectors.toSet());
        for (int i = 0; i < report.hypotheses().size(); i++) {
            Hypothesis h = report.hypotheses().get(i);
            if (h.establishedCause() && h.supportingEvidenceIds().stream().anyMatch(changes::contains)) {
                failed.add(new FailedCheck(Check.DEPLOYMENT_NOT_ESTABLISHED,
                        "hypothesis " + (i + 1) + " cites Change_Evidence and has establishedCause = true"));
            }
        }
    }

    private static Set<String> changeIds(InvestigationReport report) {
        return report.evidence().stream()
                .filter(e -> ReportRenderer.isChange(e.type()))
                .map(EvidenceRef::id)
                .collect(Collectors.toSet());
    }

    private static void deploymentEstablished(InvestigationReport report, List<FailedCheck> failed) {
        if (report.hypotheses().isEmpty()) {
            failed.add(new FailedCheck(Check.DEPLOYMENT_ESTABLISHED, "the report has no hypothesis"));
            return;
        }
        Hypothesis primary = report.hypotheses().get(0);
        Set<String> changes = changeIds(report);
        if (!primary.establishedCause() || primary.supportingEvidenceIds().stream().noneMatch(changes::contains)) {
            failed.add(new FailedCheck(Check.DEPLOYMENT_ESTABLISHED,
                    "primary hypothesis does not establish the deployment as the cause (establishedCause = "
                            + primary.establishedCause() + ")"));
        }
    }

    private static void citedChangesCorrelated(InvestigationReport report, String markdown,
            List<FailedCheck> failed) {
        Set<String> cited = new HashSet<>();
        report.hypotheses().forEach(h -> {
            cited.addAll(h.supportingEvidenceIds());
            cited.addAll(h.contradictingEvidenceIds());
        });
        report.evidence().stream()
                .filter(e -> ReportRenderer.isChange(e.type()) && cited.contains(e.id()) && e.headline() != null)
                .filter(e -> !markdown.contains(ReportRenderer.correlationStatement(e.headline())))
                .forEach(e -> failed.add(new FailedCheck(Check.CORRELATION_STATEMENT,
                        "the Markdown has no Correlation_Statement for " + e.headline())));
    }

    private static void correlationStatement(InvestigationReport report, String markdown,
            List<FailedCheck> failed) {
        List<EvidenceRef> deployments = report.evidence().stream()
                .filter(e -> ReportRenderer.isChange(e.type()))
                .filter(e -> e.headline() != null && e.headline().contains(DEMO_DEPLOYMENT))
                .toList();
        if (deployments.isEmpty()) {
            failed.add(new FailedCheck(Check.CORRELATION_STATEMENT,
                    "the report has no Change_Evidence for " + DEMO_DEPLOYMENT));
            return;
        }
        boolean stated = deployments.stream()
                .anyMatch(e -> markdown.contains(ReportRenderer.correlationStatement(e.headline())));
        if (!stated) {
            failed.add(new FailedCheck(Check.CORRELATION_STATEMENT,
                    "the Markdown has no Correlation_Statement for " + DEMO_DEPLOYMENT));
        }
    }

    private static void citationsPresent(InvestigationReport report, List<FailedCheck> failed) {
        List<TimelineEntry> entries = report.timeline() == null ? List.of() : report.timeline().entries();
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).evidenceIds().isEmpty()) {
                failed.add(new FailedCheck(Check.CITATIONS_PRESENT, "timeline entry " + (i + 1) + " cites no evidence"));
            }
        }
        for (int i = 0; i < report.hypotheses().size(); i++) {
            Hypothesis h = report.hypotheses().get(i);
            if (h.supportingEvidenceIds().isEmpty() && h.contradictingEvidenceIds().isEmpty()) {
                failed.add(new FailedCheck(Check.CITATIONS_PRESENT, "hypothesis " + (i + 1) + " cites no evidence"));
            }
        }
    }

    private static void citedEvidenceExists(InvestigationReport report, Set<String> stored,
            List<FailedCheck> failed) {
        List<String> cited = new ArrayList<>();
        if (report.timeline() != null) {
            report.timeline().entries().forEach(e -> cited.addAll(e.evidenceIds()));
        }
        for (Hypothesis h : report.hypotheses()) {
            cited.addAll(h.supportingEvidenceIds());
            cited.addAll(h.contradictingEvidenceIds());
        }
        report.actions().forEach(a -> cited.addAll(a.evidenceIds()));
        List<String> unknown = cited.stream().filter(id -> !stored.contains(id)).distinct().toList();
        if (!unknown.isEmpty()) {
            failed.add(new FailedCheck(Check.CITED_EVIDENCE_EXISTS,
                    "cited evidence IDs not stored for incident " + report.incidentId() + ": " + unknown));
        }
    }

    private static void riskyActionsRequireApproval(InvestigationReport report, List<FailedCheck> failed) {
        for (RecommendedAction a : report.actions()) {
            if (APPROVAL_TYPES.contains(a.type()) && !a.requiresApproval()) {
                failed.add(new FailedCheck(Check.RISKY_ACTIONS_REQUIRE_APPROVAL,
                        "action " + a.order() + " (" + a.type() + ") has requiresApproval = false"));
            }
        }
    }
}
