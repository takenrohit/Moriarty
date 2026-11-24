package dev.investigator.domain;

import java.time.Instant;
import java.util.List;

/**
 * One investigation of an incident (architecture §4).
 *
 * <p>In Phase 1 the repository keeps {@code evidence}, {@code hypotheses}, {@code timeline} and
 * {@code recommendedActions} empty: evidence lives in the evidence store and the analysis in
 * {@link InvestigationReport}.
 */
public record Investigation(
        String incidentId,
        InvestigationStatus status,
        Instant windowStart,
        Instant windowEnd,
        int toolCallsUsed,
        List<Evidence> evidence,
        List<Hypothesis> hypotheses,
        IncidentTimeline timeline,
        List<RecommendedAction> recommendedActions) {

    public Investigation {
        evidence = Copies.list(evidence);
        hypotheses = Copies.list(hypotheses);
        recommendedActions = Copies.list(recommendedActions);
    }
}
