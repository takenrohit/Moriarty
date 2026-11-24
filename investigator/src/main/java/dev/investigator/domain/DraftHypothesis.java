package dev.investigator.domain;

import java.util.List;

/** Hypothesis in a {@link ReportDraft}. */
public record DraftHypothesis(
        String statement,
        Confidence confidence,
        List<String> supportingEvidenceIds,
        List<String> contradictingEvidenceIds,
        boolean establishedCause) {

    public DraftHypothesis {
        supportingEvidenceIds = Copies.list(supportingEvidenceIds);
        contradictingEvidenceIds = Copies.list(contradictingEvidenceIds);
    }
}
