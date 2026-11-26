package dev.investigator.feedback;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Feedback_Service: stores a rating, an optional note and an optional submitter for an Incident's stored
 * report (Req 8.1 to 8.4). Ratings are {@code helpful}/{@code unhelpful} (design "Feedback capture");
 * repeat submissions are kept as separate rows.
 */
@Service
public class FeedbackService {

    public static final int MAX_NOTE = 2000;
    public static final int MAX_SUBMITTER = 100;

    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);

    private final FeedbackRepository repository;

    public FeedbackService(FeedbackRepository repository) {
        this.repository = repository;
    }

    /**
     * Stores one submission.
     *
     * @return the stored feedback, or empty if the Incident has no stored report (nothing is stored)
     * @throws IllegalArgumentException if the note or submitter is too long
     */
    public Optional<Feedback> submit(String incidentId, Feedback.Rating rating, String note, String submittedBy) {
        Objects.requireNonNull(incidentId, "incidentId");
        Objects.requireNonNull(rating, "rating");
        String cleanNote = blankToNull(note);
        String cleanSubmitter = blankToNull(submittedBy);
        if (cleanNote != null && cleanNote.length() > MAX_NOTE) {
            throw new IllegalArgumentException("note is longer than " + MAX_NOTE + " characters");
        }
        if (cleanSubmitter != null && cleanSubmitter.length() > MAX_SUBMITTER) {
            throw new IllegalArgumentException("submittedBy is longer than " + MAX_SUBMITTER + " characters");
        }
        Optional<Feedback> stored = repository.save(incidentId, rating, cleanNote, cleanSubmitter);
        stored.ifPresent(f -> log.info("feedback stored incident_id={} feedback_id={} rating={}", incidentId,
                f.id(), rating));
        return stored;
    }

    /** All feedback on the Incident's reports, oldest first. */
    public List<Feedback> list(String incidentId) {
        return repository.findByIncident(incidentId);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
