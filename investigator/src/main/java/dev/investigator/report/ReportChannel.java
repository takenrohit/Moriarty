package dev.investigator.report;

import dev.investigator.domain.InvestigationReport;

/**
 * Report_Channel: one destination of a stored Investigation_Report (CP-47).
 *
 * <p>{@link ReportPublisher} calls every channel bean in {@code @Order} order, each inside its own
 * try/catch, so a channel may throw: the failure is logged with {@link #name()} and never affects the
 * stored report or the other channels (Req 13.3, 13.4). Exception messages must not contain secrets.
 */
public interface ReportChannel {

    /** Short channel name for log lines, for example {@code log} or {@code slack}. */
    String name();

    /**
     * Publishes one stored report.
     *
     * @param report the stored report
     * @param view its display model, already rendered in the Display_Timezone
     */
    void publish(InvestigationReport report, ReportView view);
}
