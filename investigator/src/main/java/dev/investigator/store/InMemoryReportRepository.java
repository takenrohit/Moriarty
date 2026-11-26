package dev.investigator.store;

import dev.investigator.domain.InvestigationReport;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Repository;

/**
 * In-memory {@link ReportRepository}: an immutable list of versions per Incident, replaced inside
 * {@link ConcurrentMap#compute}, so concurrent writers cannot lose a version.
 */
@Repository
public class InMemoryReportRepository implements ReportRepository {

    private final ConcurrentMap<String, List<InvestigationReport>> reports = new ConcurrentHashMap<>();

    @Override
    public boolean saveIfAbsent(InvestigationReport report) {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(report.incidentId(), "report.incidentId");
        return reports.putIfAbsent(report.incidentId(), List.of(report)) == null;
    }

    @Override
    public int append(InvestigationReport report) {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(report.incidentId(), "report.incidentId");
        return reports.compute(report.incidentId(), (id, versions) -> {
            List<InvestigationReport> next = versions == null ? new ArrayList<>() : new ArrayList<>(versions);
            next.add(report);
            return List.copyOf(next);
        }).size();
    }

    @Override
    public Optional<InvestigationReport> find(String incidentId) {
        List<InvestigationReport> versions = incidentId == null ? null : reports.get(incidentId);
        return versions == null ? Optional.empty() : Optional.of(versions.get(versions.size() - 1));
    }

    @Override
    public Optional<InvestigationReport> find(String incidentId, int version) {
        List<InvestigationReport> versions = incidentId == null ? null : reports.get(incidentId);
        return versions == null || version < 1 || version > versions.size()
                ? Optional.empty() : Optional.of(versions.get(version - 1));
    }
}
