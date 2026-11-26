package dev.investigator.report;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** In-memory {@link ApprovalRepository}. */
@Repository
@Profile("!postgres")
public class InMemoryApprovalRepository implements ApprovalRepository {

    private final Map<String, Approval> approvals = new ConcurrentHashMap<>();

    @Override
    public void save(Approval pending) {
        if (approvals.putIfAbsent(pending.id(), pending) != null) {
            throw new IllegalStateException("approval ID already exists");
        }
    }

    @Override
    public Optional<Approval> find(String approvalId) {
        return Optional.ofNullable(approvals.get(approvalId));
    }

    @Override
    public boolean decide(String approvalId, Approval.Status decision, String decidedBy, Instant decidedAt) {
        AtomicBoolean recorded = new AtomicBoolean();
        approvals.computeIfPresent(approvalId, (id, current) -> {
            if (current.status() != Approval.Status.PENDING) {
                return current;
            }
            recorded.set(true);
            return current.decided(decision, decidedBy, decidedAt);
        });
        return recorded.get();
    }
}
