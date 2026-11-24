package dev.investigator.domain;

import java.util.List;

/** Action in a {@link ReportDraft}; the model's {@code requiresApproval} is replaced by the Action_Policy. */
public record DraftAction(
        int order,
        ActionType type,
        String description,
        boolean requiresApproval,
        List<String> evidenceIds) {

    public DraftAction {
        evidenceIds = Copies.list(evidenceIds);
    }
}
