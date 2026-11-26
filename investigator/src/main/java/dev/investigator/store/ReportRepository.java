package dev.investigator.store;

import dev.investigator.domain.InvestigationReport;
import java.util.Optional;

/**
 * Stores the Investigation_Reports of each Incident as numbered versions starting at 1 (Req 6.1, 9.4,
 * 14.9). The first investigation stores version 1 with {@link #saveIfAbsent}; each re-investigation adds
 * the next version with {@link #append}. {@link #find} returns the latest of these.
 */
public interface ReportRepository {

    /** Stores the report unless the Incident already has one; returns false (and changes nothing) if it does. */
    boolean saveIfAbsent(InvestigationReport report);

    /** Stores the report as the Incident's next version and returns that version (1 if it had none). */
    int append(InvestigationReport report);

    /** The Incident's latest stored report. */
    Optional<InvestigationReport> find(String incidentId);

    /** The Incident's report with this version. */
    Optional<InvestigationReport> find(String incidentId, int version);
}
