package dev.investigator.store;

import dev.investigator.domain.Investigation;
import dev.investigator.domain.InvestigationStatus;
import dev.investigator.investigation.StatusTransitions;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Repository;

/**
 * In-memory {@link InvestigationRepository} (Phase 1). {@link #transition} is a compare-and-set
 * inside {@link ConcurrentMap#compute}, so concurrent callers cannot both leave the same status.
 */
@Repository
public class InMemoryInvestigationRepository implements InvestigationRepository {

    private final ConcurrentMap<String, Investigation> investigations = new ConcurrentHashMap<>();

    @Override
    public void create(Investigation investigation) {
        Objects.requireNonNull(investigation, "investigation");
        Objects.requireNonNull(investigation.incidentId(), "investigation.incidentId");
        if (investigation.status() != InvestigationStatus.PENDING) {
            throw new IllegalArgumentException("a new investigation must be PENDING, was " + investigation.status());
        }
        if (investigations.putIfAbsent(investigation.incidentId(), investigation) != null) {
            throw new IllegalStateException("investigation already exists for " + investigation.incidentId());
        }
    }

    @Override
    public Optional<Investigation> find(String incidentId) {
        return incidentId == null ? Optional.empty() : Optional.ofNullable(investigations.get(incidentId));
    }

    @Override
    public boolean transition(String incidentId, InvestigationStatus from, InvestigationStatus to) {
        if (incidentId == null || !StatusTransitions.isAllowed(from, to)) {
            return false;
        }
        boolean[] moved = {false};
        investigations.computeIfPresent(incidentId, (key, current) -> {
            if (current.status() != from) {
                return current;
            }
            moved[0] = true;
            return withStatus(current, to);
        });
        return moved[0];
    }

    @Override
    public void recordToolCalls(String incidentId, int used) {
        if (used < 0) {
            throw new IllegalArgumentException("used must not be negative: " + used);
        }
        Investigation updated = incidentId == null ? null : investigations.computeIfPresent(incidentId,
                (key, current) -> new Investigation(current.incidentId(), current.status(), current.windowStart(),
                        current.windowEnd(), used, current.evidence(), current.hypotheses(), current.timeline(),
                        current.recommendedActions()));
        if (updated == null) {
            throw new NoSuchElementException("no investigation for " + incidentId);
        }
    }

    private static Investigation withStatus(Investigation current, InvestigationStatus status) {
        return new Investigation(current.incidentId(), status, current.windowStart(), current.windowEnd(),
                current.toolCallsUsed(), current.evidence(), current.hypotheses(), current.timeline(),
                current.recommendedActions());
    }
}
