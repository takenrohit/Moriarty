package dev.investigator.report;

import java.time.Instant;
import java.util.Objects;

/**
 * One approval request for an Approvable_Action and, once decided, its decision (Req 7.1, 7.4).
 *
 * @param id approval ID, {@code apr-} followed by 12 lowercase hex characters; the Slack button value
 * @param incidentId Incident the report belongs to
 * @param action short description of the action, for example {@code #2 ROLLBACK: roll back v2}
 * @param status {@code PENDING} until decided
 * @param requestedAt when the request was created
 * @param decidedBy who decided, {@code null} while pending
 * @param decidedAt when it was decided, {@code null} while pending
 */
public record Approval(String id, String incidentId, String action, Status status, Instant requestedAt,
        String decidedBy, Instant decidedAt) {

    /** Approval states; a decided approval never changes again. */
    public enum Status {
        PENDING, APPROVED, REJECTED
    }

    public Approval {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(requestedAt, "requestedAt");
    }

    /** A new pending request. */
    public static Approval pending(String id, String incidentId, String action, Instant requestedAt) {
        return new Approval(id, incidentId, action, Status.PENDING, requestedAt, null, null);
    }

    /** This request with the decision applied. */
    public Approval decided(Status decision, String by, Instant at) {
        return new Approval(id, incidentId, action, decision, requestedAt, by, at);
    }
}
