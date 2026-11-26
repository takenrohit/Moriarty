package dev.investigator.feedback;

import dev.investigator.store.ReportRepository;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/** In-memory {@link FeedbackRepository}; a report must be stored in the {@link ReportRepository}. */
@Repository
@Profile("!postgres")
public class InMemoryFeedbackRepository implements FeedbackRepository {

    private final ReportRepository reports;
    private final Clock clock;
    private final AtomicLong ids = new AtomicLong();
    private final List<Feedback> feedback = new CopyOnWriteArrayList<>();

    public InMemoryFeedbackRepository(ReportRepository reports, Clock clock) {
        this.reports = reports;
        this.clock = clock;
    }

    @Override
    public Optional<Feedback> save(String incidentId, Feedback.Rating rating, String note, String submittedBy) {
        if (reports.find(incidentId).isEmpty()) {
            return Optional.empty();
        }
        Feedback stored = new Feedback(ids.incrementAndGet(), incidentId, rating, note, submittedBy, clock.instant());
        feedback.add(stored);
        return Optional.of(stored);
    }

    @Override
    public List<Feedback> findByIncident(String incidentId) {
        return feedback.stream().filter(f -> f.incidentId().equals(incidentId)).toList();
    }
}
