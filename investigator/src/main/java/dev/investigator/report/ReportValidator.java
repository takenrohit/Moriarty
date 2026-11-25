package dev.investigator.report;

import dev.investigator.domain.DraftAction;
import dev.investigator.domain.DraftHypothesis;
import dev.investigator.domain.DraftTimelineEntry;
import dev.investigator.domain.Hypothesis;
import dev.investigator.domain.InvestigationReport;
import dev.investigator.domain.InvestigationStatus;
import dev.investigator.domain.RecommendedAction;
import dev.investigator.domain.ReportDraft;
import dev.investigator.domain.TimelineEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Report_Validator: the semantic checks the Report_Draft_Schema cannot express (Req 15.6 to 15.8,
 * 15.12), plus a last check of the final report before it is stored.
 *
 * <p>Both methods collect every violation instead of stopping at the first. Each violation starts with
 * the JSON path it concerns; draft paths use the draft's field names and indexes, report paths the
 * report's JSON (for example {@code $.timeline.entries[0]}). Cited IDs are not echoed, the path names
 * them.
 */
@Component
public final class ReportValidator {

    /**
     * Most open questions a stored report may have: the draft's limit of
     * {@value FallbackReportBuilder#MAX_OPEN_QUESTIONS} plus the orchestrator's budget note (Req 14.4).
     */
    public static final int MAX_REPORT_OPEN_QUESTIONS = FallbackReportBuilder.MAX_OPEN_QUESTIONS + 1;

    private static final Set<InvestigationStatus> REPORT_STATUSES = Set.of(InvestigationStatus.COMPLETED,
            InvestigationStatus.INCONCLUSIVE, InvestigationStatus.FAILED);

    static final String TIMELINE_UNCITED = "a timeline entry must cite at least one evidence ID";
    static final String HYPOTHESIS_UNCITED = "a hypothesis must cite at least one supporting evidence ID";

    /**
     * Checks a parsed draft against the Investigation's Incident_ID: the draft's {@code incidentId}
     * matches; every timeline entry cites at least one Evidence_ID; every hypothesis has at least one
     * supporting Evidence_ID; and every cited Evidence_ID (timeline, hypotheses, actions) has the form
     * {@code ev-<incidentId>-<n>} with {@code n ≥ 1}.
     */
    public ValidationResult validateDraft(ReportDraft draft, String incidentId) {
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(incidentId, "incidentId");
        List<String> violations = new ArrayList<>();
        Pattern ids = evidenceIdPattern(incidentId);

        if (!incidentId.equals(draft.incidentId())) {
            violations.add("$.incidentId: must be the investigation's incident ID " + incidentId);
        }
        for (int i = 0; i < draft.timeline().size(); i++) {
            DraftTimelineEntry entry = draft.timeline().get(i);
            String path = "$.timeline[" + i + "].evidenceIds";
            requireCitation(violations, path, entry.evidenceIds(), TIMELINE_UNCITED);
            checkIds(violations, path, entry.evidenceIds(), ids, incidentId);
        }
        for (int i = 0; i < draft.hypotheses().size(); i++) {
            DraftHypothesis h = draft.hypotheses().get(i);
            String path = "$.hypotheses[" + i + "]";
            requireCitation(violations, path + ".supportingEvidenceIds", h.supportingEvidenceIds(), HYPOTHESIS_UNCITED);
            checkIds(violations, path + ".supportingEvidenceIds", h.supportingEvidenceIds(), ids, incidentId);
            checkIds(violations, path + ".contradictingEvidenceIds", h.contradictingEvidenceIds(), ids, incidentId);
        }
        for (int i = 0; i < draft.actions().size(); i++) {
            DraftAction a = draft.actions().get(i);
            checkIds(violations, "$.actions[" + i + "].evidenceIds", a.evidenceIds(), ids, incidentId);
        }
        return new ValidationResult(violations);
    }

    /**
     * Re-checks the draft invariants on the final report, plus: the status is {@code COMPLETED},
     * {@code INCONCLUSIVE} or {@code FAILED}; summary, generation time, timeline times, hypothesis
     * confidences and action types are present; every action's {@code requiresApproval} follows
     * {@link ActionPolicy#requiresApproval}; and there are at most {@value #MAX_REPORT_OPEN_QUESTIONS}
     * open questions.
     */
    public ValidationResult validateReport(InvestigationReport report) {
        Objects.requireNonNull(report, "report");
        List<String> violations = new ArrayList<>();
        String incidentId = report.incidentId();
        Pattern ids = null;
        if (incidentId == null || incidentId.isBlank()) {
            violations.add("$.incidentId: is required");
        } else {
            ids = evidenceIdPattern(incidentId);
        }
        if (!REPORT_STATUSES.contains(report.status())) {
            violations.add("$.status: must be COMPLETED, INCONCLUSIVE or FAILED");
        }
        if (report.summary() == null || report.summary().isBlank()) {
            violations.add("$.summary: is required");
        }
        if (report.generatedAt() == null) {
            violations.add("$.generatedAt: is required");
        }

        if (report.timeline() == null) {
            violations.add("$.timeline: is required");
        } else {
            List<TimelineEntry> entries = report.timeline().entries();
            for (int i = 0; i < entries.size(); i++) {
                TimelineEntry entry = entries.get(i);
                String path = "$.timeline.entries[" + i + "]";
                if (entry.at() == null) {
                    violations.add(path + ".at: is required");
                }
                requireCitation(violations, path + ".evidenceIds", entry.evidenceIds(), TIMELINE_UNCITED);
                checkIds(violations, path + ".evidenceIds", entry.evidenceIds(), ids, incidentId);
            }
        }
        for (int i = 0; i < report.hypotheses().size(); i++) {
            Hypothesis h = report.hypotheses().get(i);
            String path = "$.hypotheses[" + i + "]";
            if (h.confidence() == null) {
                violations.add(path + ".confidence: is required");
            }
            requireCitation(violations, path + ".supportingEvidenceIds", h.supportingEvidenceIds(), HYPOTHESIS_UNCITED);
            checkIds(violations, path + ".supportingEvidenceIds", h.supportingEvidenceIds(), ids, incidentId);
            checkIds(violations, path + ".contradictingEvidenceIds", h.contradictingEvidenceIds(), ids, incidentId);
        }
        for (int i = 0; i < report.actions().size(); i++) {
            RecommendedAction a = report.actions().get(i);
            String path = "$.actions[" + i + "]";
            if (a.type() == null) {
                violations.add(path + ".type: is required");
            }
            boolean required = ActionPolicy.requiresApproval(a.type());
            if (a.requiresApproval() != required) {
                violations.add(path + ".requiresApproval: must be " + required + " for "
                        + (a.type() == null ? "untyped" : a.type().name()) + " actions");
            }
            checkIds(violations, path + ".evidenceIds", a.evidenceIds(), ids, incidentId);
        }
        if (report.openQuestions().size() > MAX_REPORT_OPEN_QUESTIONS) {
            violations.add("$.openQuestions: must have at most " + MAX_REPORT_OPEN_QUESTIONS + " items");
        }
        return new ValidationResult(violations);
    }

    /** {@code ^ev-<incidentId>-[1-9][0-9]*$} with the Incident_ID matched literally. */
    static Pattern evidenceIdPattern(String incidentId) {
        return Pattern.compile("ev-" + Pattern.quote(incidentId) + "-[1-9][0-9]*");
    }

    private static void requireCitation(List<String> violations, String path, List<String> cited, String rule) {
        if (cited.isEmpty()) {
            violations.add(path + ": " + rule);
        }
    }

    private static void checkIds(List<String> violations, String path, List<String> cited, Pattern ids,
            String incidentId) {
        if (ids == null) {
            return;
        }
        for (int i = 0; i < cited.size(); i++) {
            String id = cited.get(i);
            if (id == null || !ids.matcher(id).matches()) {
                violations.add(path + "[" + i + "]: is not an evidence ID of incident " + incidentId
                        + " (expected ev-" + incidentId + "-<n>)");
            }
        }
    }
}
