package dev.investigator.store;

import dev.investigator.domain.Evidence;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Repository;

/**
 * In-memory {@link EvidenceRepository} (Phase 1).
 *
 * <p>The ID index and the per-incident list are updated inside one {@code compute} on the
 * per-incident entry, so a saved record is visible through both lookups once {@link #save} returns.
 */
@Repository
public class InMemoryEvidenceRepository implements EvidenceRepository {

    private final ConcurrentMap<String, Evidence> byId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<Evidence>> byIncident = new ConcurrentHashMap<>();

    @Override
    public void save(Evidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(evidence.id(), "evidence.id");
        Objects.requireNonNull(evidence.incidentId(), "evidence.incidentId");
        byIncident.compute(evidence.incidentId(), (incidentId, list) -> {
            if (byId.putIfAbsent(evidence.id(), evidence) != null) {
                throw new IllegalStateException("evidence already stored: " + evidence.id());
            }
            List<Evidence> target = list == null ? new CopyOnWriteArrayList<>() : list;
            target.add(evidence);
            return target;
        });
    }

    @Override
    public List<Evidence> findByIncident(String incidentId) {
        List<Evidence> list = incidentId == null ? null : byIncident.get(incidentId);
        return list == null ? List.of() : List.copyOf(list);
    }

    @Override
    public Optional<Evidence> find(String evidenceId) {
        return evidenceId == null ? Optional.empty() : Optional.ofNullable(byId.get(evidenceId));
    }
}
