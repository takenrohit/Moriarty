package dev.investigator.report;

import java.time.Instant;
import java.util.Optional;

/** Store of {@link Approval}s: in memory by default, the {@code approvals} table under {@code postgres}. */
public interface ApprovalRepository {

    /** Stores a new {@code PENDING} approval. */
    void save(Approval pending);

    /** The current state of an approval: its decision once decided, else the pending request. */
    Optional<Approval> find(String approvalId);

    /**
     * Records {@code decision} if the approval exists and is still {@code PENDING}; atomic, so of two
     * concurrent decisions only one is recorded.
     *
     * @return whether this call recorded the decision
     */
    boolean decide(String approvalId, Approval.Status decision, String decidedBy, Instant decidedAt);
}
