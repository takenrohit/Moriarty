package dev.investigator.domain;

import java.util.List;

/** A candidate explanation with supporting and contradicting evidence (architecture §4). */
public record Hypothesis(
        String statement,
        Confidence confidence,
        List<String> supportingEvidenceIds,
        List<String> contradictingEvidenceIds,
        boolean establishedCause) {

    public Hypothesis {
        supportingEvidenceIds = Copies.list(supportingEvidenceIds);
        contradictingEvidenceIds = Copies.list(contradictingEvidenceIds);
    }
}
