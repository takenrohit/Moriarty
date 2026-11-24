package dev.investigator.domain;

import java.util.List;

/** Report draft returned by the model as JSON, before validation and assembly (CP-3). */
public record ReportDraft(
        String incidentId,
        String summary,
        List<DraftTimelineEntry> timeline,
        List<DraftHypothesis> hypotheses,
        List<DraftAction> actions,
        List<String> openQuestions) {

    public ReportDraft {
        timeline = Copies.list(timeline);
        hypotheses = Copies.list(hypotheses);
        actions = Copies.list(actions);
        openQuestions = Copies.list(openQuestions);
    }
}
