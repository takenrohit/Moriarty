package dev.investigator.store;

import dev.investigator.domain.InvestigationReport;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Repository;

/** In-memory {@link ReportRepository} (Phase 1); {@code putIfAbsent} enforces one report per Incident. */
@Repository
public class InMemoryReportRepository implements ReportRepository {

    private final ConcurrentMap<String, InvestigationReport> reports = new ConcurrentHashMap<>();

    @Override
    public boolean saveIfAbsent(InvestigationReport report) {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(report.incidentId(), "report.incidentId");
        return reports.putIfAbsent(report.incidentId(), report) == null;
    }

    @Override
    public Optional<InvestigationReport> find(String incidentId) {
        return incidentId == null ? Optional.empty() : Optional.ofNullable(reports.get(incidentId));
    }
}
