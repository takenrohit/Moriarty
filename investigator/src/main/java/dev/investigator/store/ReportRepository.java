package dev.investigator.store;

import dev.investigator.domain.InvestigationReport;
import java.util.Optional;

/** Stores at most one Investigation_Report per Incident (Req 6.1, 14.9). */
public interface ReportRepository {

    /** Stores the report unless the Incident already has one; returns false (and changes nothing) if it does. */
    boolean saveIfAbsent(InvestigationReport report);

    Optional<InvestigationReport> find(String incidentId);
}
