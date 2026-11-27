package dev.investigator.web;

import dev.investigator.report.Approval;
import dev.investigator.report.DisplayTimeFormatter;
import dev.investigator.report.ReportView;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * One recommended action on the Report_Page with its read-only Approval_State (web UI Req 7).
 *
 * @param action the action as rendered for the report
 * @param requiresApproval whether the action is an Approvable_Action
 * @param approvalState {@code PENDING}, {@code APPROVED} or {@code REJECTED} when {@code requiresApproval},
 *     else {@code null}
 * @param decidedBy who decided; non-null only for {@code APPROVED} or {@code REJECTED}
 * @param decidedTime decision time in the Display_Timezone; non-null only for a decided approval with a time
 */
public record ActionApprovalView(ReportView.ActionView action, boolean requiresApproval, String approvalState,
        String decidedBy, String decidedTime) {

    /** Approval_State shown when no Approval row exists for an approvable action (Req 7.4). */
    static final String PENDING = Approval.Status.PENDING.name();

    /**
     * The view of every action in {@code actions}, each approvable one paired with the newest Approval of
     * {@code approvals} whose action text starts with {@code "#<order> "} (the format written by
     * {@code ApprovalService}), or {@code PENDING} when there is none (Req 7.2 to 7.4).
     */
    static List<ActionApprovalView> of(List<ReportView.ActionView> actions, List<Approval> approvals,
            DisplayTimeFormatter times) {
        return actions.stream().map(action -> of(action, approvals, times)).toList();
    }

    private static ActionApprovalView of(ReportView.ActionView action, List<Approval> approvals,
            DisplayTimeFormatter times) {
        if (!action.requiresApproval()) {
            return new ActionApprovalView(action, false, null, null, null);
        }
        String prefix = "#" + action.order() + " ";
        Approval match = approvals.stream()
                .filter(Objects::nonNull)
                .filter(a -> a.action() != null && a.action().startsWith(prefix))
                .max(Comparator.comparing(Approval::requestedAt).thenComparing(Approval::id))
                .orElse(null);
        if (match == null) {
            return new ActionApprovalView(action, true, PENDING, null, null);
        }
        boolean decided = match.status() != Approval.Status.PENDING;
        return new ActionApprovalView(action, true, match.status().name(),
                decided ? match.decidedBy() : null,
                decided && match.decidedAt() != null ? times.dateTime(match.decidedAt()) : null);
    }
}
