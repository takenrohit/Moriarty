package dev.investigator.domain;

import java.time.Instant;
import java.util.List;

/**
 * The final report of an investigation (architecture §4, CP-2).
 *
 * @param status   investigation status, added for INCONCLUSIVE marking (FR-16)
 * @param evidence references to all stored evidence, added for the evidence list (FR-20)
 */
public record InvestigationReport(
        String incidentId,
        InvestigationStatus status,
        String summary,
        IncidentTimeline timeline,
        List<Hypothesis> hypotheses,
        List<RecommendedAction> actions,
        List<String> openQuestions,
        List<EvidenceRef> evidence,
        Instant generatedAt) {

    public InvestigationReport {
        hypotheses = Copies.list(hypotheses);
        actions = Copies.list(actions);
        openQuestions = Copies.list(openQuestions);
        evidence = Copies.list(evidence);
    }
}
