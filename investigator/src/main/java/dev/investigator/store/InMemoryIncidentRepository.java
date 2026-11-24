package dev.investigator.store;

import dev.investigator.domain.Incident;
import dev.investigator.domain.IncidentStatus;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Repository;

/** In-memory {@link IncidentRepository} (Phase 1). */
@Repository
public class InMemoryIncidentRepository implements IncidentRepository {

    private static final Comparator<Incident> NEWEST_FIRST = Comparator
            .comparing(Incident::detectedAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(Incident::id);

    private final ConcurrentMap<String, Incident> incidents = new ConcurrentHashMap<>();

    @Override
    public void save(Incident incident) {
        Objects.requireNonNull(incident, "incident");
        Objects.requireNonNull(incident.id(), "incident.id");
        if (incidents.putIfAbsent(incident.id(), incident) != null) {
            throw new IllegalStateException("incident already stored: " + incident.id());
        }
    }

    @Override
    public Optional<Incident> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(incidents.get(id));
    }

    @Override
    public List<Incident> findAllNewestFirst() {
        return incidents.values().stream().sorted(NEWEST_FIRST).toList();
    }

    @Override
    public void updateStatus(String id, IncidentStatus status) {
        Objects.requireNonNull(status, "status");
        Incident updated = incidents.computeIfPresent(id, (key, incident) -> new Incident(
                incident.id(), incident.title(), incident.severity(), incident.affectedService(),
                incident.detectedAt(), incident.alertName(), incident.labels(), incident.dashboardLinks(),
                incident.fingerprint(), status));
        if (updated == null) {
            throw new NoSuchElementException("no incident " + id);
        }
    }
}
