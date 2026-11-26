package dev.investigator.report;

import dev.investigator.domain.InvestigationReport;
import dev.investigator.domain.RecommendedAction;
import dev.investigator.observability.InvestigatorMetrics;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * Approval_Service: creates a {@code PENDING} {@link Approval} for each Approvable_Action of a report
 * posted to Slack, and records the Approve/Reject decision from a verified Slack callback once
 * (Req 7.1, 7.4, 2.5).
 *
 * <p>It never executes a recommended action (Req 7.5): a decision is only stored, logged and timed.
 * The bean exists only under the same non-blank bot token and channel condition as
 * {@link SlackReportChannel}, so without Slack no approvals are created (Req 7.6, CP-68).
 */
@Component
@Conditional(SlackReportChannel.OnSlackConfigured.class)
public class ApprovalService {

    /** Pattern of every approval ID. */
    public static final Pattern ID_PATTERN = Pattern.compile("apr-[0-9a-f]{12}");

    /** At most this many approvals per report, so the Slack message stays within its block limit. */
    static final int MAX_APPROVALS_PER_REPORT = 5;

    private static final int MAX_ACTION_TEXT = 600;
    private static final HexFormat HEX = HexFormat.of();
    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    /** Result of {@link #decide}. */
    public enum Outcome {
        RECORDED, ALREADY_DECIDED, NOT_FOUND
    }

    /**
     * @param outcome what happened
     * @param approval the approval's current state, {@code null} when not found
     */
    public record Decision(Outcome outcome, Approval approval) {
    }

    private final ApprovalRepository repository;
    private final Clock clock;
    private final InvestigatorMetrics metrics;
    private final Random random = new SecureRandom();

    public ApprovalService(ApprovalRepository repository, Clock clock, InvestigatorMetrics metrics) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * Stores a {@code PENDING} approval for each action that requires approval, in action order, at most
     * {@value #MAX_APPROVALS_PER_REPORT}. An approval that cannot be stored is logged and left out, so
     * its buttons are not posted.
     */
    public List<Approval> request(InvestigationReport report) {
        if (report == null || report.actions() == null) {
            return List.of();
        }
        List<Approval> created = new ArrayList<>();
        for (RecommendedAction action : report.actions()) {
            if (!action.requiresApproval() && !ActionPolicy.requiresApproval(action.type())) {
                continue;
            }
            if (created.size() == MAX_APPROVALS_PER_REPORT) {
                log.warn("approval limit reached incident_id={} limit={}; further actions get no buttons",
                        report.incidentId(), MAX_APPROVALS_PER_REPORT);
                break;
            }
            Approval approval = Approval.pending(nextId(), report.incidentId(), describe(action), clock.instant());
            try {
                repository.save(approval);
                created.add(approval);
                log.info("approval requested approval_id={} incident_id={} action_order={}", approval.id(),
                        report.incidentId(), action.order());
            } catch (RuntimeException e) {
                log.warn("could not store approval incident_id={} action_order={} reason={}", report.incidentId(),
                        action.order(), e.getClass().getSimpleName());
            }
        }
        return created;
    }

    /**
     * Records {@code decision} ({@code APPROVED} or {@code REJECTED}) by {@code decidedBy} if the approval
     * is still pending, and records the approval-wait metric. Executes nothing.
     */
    public Decision decide(String approvalId, Approval.Status decision, String decidedBy) {
        if (decision == null || decision == Approval.Status.PENDING) {
            throw new IllegalArgumentException("decision must be APPROVED or REJECTED");
        }
        Optional<Approval> current = repository.find(approvalId);
        if (current.isEmpty()) {
            return new Decision(Outcome.NOT_FOUND, null);
        }
        if (current.get().status() != Approval.Status.PENDING) {
            return new Decision(Outcome.ALREADY_DECIDED, current.get());
        }
        Instant now = clock.instant();
        if (!repository.decide(approvalId, decision, decidedBy, now)) {
            return new Decision(Outcome.ALREADY_DECIDED, repository.find(approvalId).orElse(current.get()));
        }
        Approval decided = current.get().decided(decision, decidedBy, now);
        Duration wait = Duration.between(decided.requestedAt(), now);
        metrics.approvalWait(wait.isNegative() ? Duration.ZERO : wait);
        log.info("approval decided approval_id={} incident_id={} decision={} wait_ms={}; no action is executed",
                approvalId, decided.incidentId(), decision, wait.toMillis());
        return new Decision(Outcome.RECORDED, decided);
    }

    private String nextId() {
        byte[] bytes = new byte[6];
        random.nextBytes(bytes);
        return "apr-" + HEX.formatHex(bytes);
    }

    private static String describe(RecommendedAction action) {
        String type = action.type() == null ? "UNKNOWN" : action.type().name();
        String text = "#" + action.order() + " " + type + ": "
                + (action.description() == null ? "" : action.description().strip());
        return SlackBlockKitRenderer.cut(text, MAX_ACTION_TEXT);
    }
}
