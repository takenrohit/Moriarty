package dev.investigator.report;

import java.util.List;

/**
 * Display model of one Investigation_Report for the Markdown rendering and the Thymeleaf page.
 *
 * <p>All times are strings in the Display_Timezone (Req 17.5). Text is raw: the Markdown output writes
 * it as is and the templates escape it with {@code th:text} (Req 17.8).
 *
 * @param severity severity in title case, for example {@code Critical}
 * @param detected detection time with date and zone, for example {@code 2026-11-23 20:10 IST}
 * @param status Investigation status of the report, for example {@code COMPLETED}
 * @param timelineHeading {@code Timeline (IST)} when all times share the detection date and zone
 *     abbreviation (times are then {@code HH:mm}), otherwise {@code Timeline} with full date-times
 */
public record ReportView(
        String incidentId,
        String title,
        String service,
        String severity,
        String detected,
        String status,
        String generated,
        String summary,
        List<HypothesisView> hypotheses,
        String timelineHeading,
        List<TimelineEntryView> timeline,
        List<ActionView> actions,
        List<String> openQuestions,
        List<EvidenceView> evidence) {

    public ReportView {
        hypotheses = List.copyOf(hypotheses);
        timeline = List.copyOf(timeline);
        actions = List.copyOf(actions);
        openQuestions = List.copyOf(openQuestions);
        evidence = List.copyOf(evidence);
    }

    /**
     * One hypothesis, numbered from 1.
     *
     * @param correlationStatements Correlation_Statements rendered under this hypothesis (Req 16.7);
     *     each change appears under the first unestablished hypothesis that cites it
     */
    public record HypothesisView(int number, String statement, String confidence, List<String> supportingEvidenceIds,
            List<String> contradictingEvidenceIds, boolean establishedCause, List<String> correlationStatements) {

        public HypothesisView {
            supportingEvidenceIds = List.copyOf(supportingEvidenceIds);
            contradictingEvidenceIds = List.copyOf(contradictingEvidenceIds);
            correlationStatements = List.copyOf(correlationStatements);
        }
    }

    /** One timeline entry with its display time. */
    public record TimelineEntryView(String time, String description, List<String> evidenceIds) {

        public TimelineEntryView {
            evidenceIds = List.copyOf(evidenceIds);
        }
    }

    /**
     * One recommended action.
     *
     * @param marker {@code [investigate]} or, for actions needing approval, {@code [rollback, requires approval]}
     */
    public record ActionView(int order, String type, String description, boolean requiresApproval, String marker,
            List<String> evidenceIds) {

        public ActionView {
            evidenceIds = List.copyOf(evidenceIds);
        }
    }

    /** One stored Evidence reference with its display time. */
    public record EvidenceView(String id, String type, String source, String observedAt, String headline,
            String citation) {
    }
}
