package dev.investigator.report;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    public List<Approval> findByIncident(String incidentId) {
        return approvals.values().stream()
                .filter(a -> Objects.equals(a.incidentId(), incidentId))
                .sorted(Comparator.comparing(Approval::requestedAt).thenComparing(Approval::id))
                .toList();
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
