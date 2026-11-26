package dev.investigator.feedback;

import java.util.List;
import java.util.Optional;

/** Stores feedback on reports; any number of submissions per report (Req 8.1, 8.4). */
public interface FeedbackRepository {

    /**
     * Stores the feedback against the Incident's current report.
     *
     * @return the stored feedback with its ID, or empty if the Incident has no stored report
     */
    Optional<Feedback> save(String incidentId, Feedback.Rating rating, String note, String submittedBy);

    /** All feedback on the Incident's reports, oldest first. */
    List<Feedback> findByIncident(String incidentId);
}
