package dev.investigator.investigation;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/** In-memory {@link InvestigationContextRegistry} (Phase 1, CP-15). */
@Component
public class InMemoryInvestigationContextRegistry implements InvestigationContextRegistry {

    private final ConcurrentMap<String, InvestigationContext> contexts = new ConcurrentHashMap<>();

    @Override
    public void register(InvestigationContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        contexts.put(ctx.incidentId(), ctx);
    }

    @Override
    public Optional<InvestigationContext> find(String incidentId) {
        return incidentId == null ? Optional.empty() : Optional.ofNullable(contexts.get(incidentId));
    }

    @Override
    public void remove(String incidentId) {
        if (incidentId != null) {
            contexts.remove(incidentId);
        }
    }
}
