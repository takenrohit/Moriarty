package dev.investigator.domain;

import java.util.List;

/** A typed next step; {@code requiresApproval} is set by the Action_Policy (architecture §4, FR-41). */
public record RecommendedAction(
        int order,
        ActionType type,
        String description,
        boolean requiresApproval,
        List<String> evidenceIds) {

    public RecommendedAction {
        evidenceIds = Copies.list(evidenceIds);
    }
}
