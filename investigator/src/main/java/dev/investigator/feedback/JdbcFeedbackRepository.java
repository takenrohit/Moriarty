package dev.investigator.feedback;

import dev.investigator.store.jdbc.PgValues;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * PostgreSQL {@link FeedbackRepository} over the existing {@code feedback} table (Req 8.1).
 * {@code report_id} is the {@code reports.id} of the Incident's highest report version; the insert selects
 * it, so an Incident without a report inserts nothing. {@code user_name} holds the submitter.
 */
@Repository
@Profile("postgres")
public class JdbcFeedbackRepository implements FeedbackRepository {

    private final JdbcClient jdbc;

    public JdbcFeedbackRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Feedback> save(String incidentId, Feedback.Rating rating, String note, String submittedBy) {
        return jdbc.sql("""
                INSERT INTO feedback (report_id, rating, note, user_name)
                SELECT id, :rating, :note, :userName
                  FROM reports WHERE incident_id = :incidentId
                 ORDER BY version DESC LIMIT 1
                RETURNING id, rating, note, user_name, created_at
                """)
                .param("incidentId", incidentId)
                .param("rating", rating.code())
                .param("note", PgValues.text(note))
                .param("userName", PgValues.text(submittedBy))
                .query((rs, n) -> read(rs, incidentId))
                .optional();
    }

    @Override
    public List<Feedback> findByIncident(String incidentId) {
        return jdbc.sql("""
                SELECT f.id, f.rating, f.note, f.user_name, f.created_at
                  FROM feedback f JOIN reports r ON r.id = f.report_id
                 WHERE r.incident_id = :incidentId
                 ORDER BY f.id
                """)
                .param("incidentId", incidentId)
                .query((rs, n) -> read(rs, incidentId))
                .list();
    }

    private static Feedback read(ResultSet rs, String incidentId) throws SQLException {
        return new Feedback(rs.getLong("id"), incidentId, Feedback.Rating.ofCode(rs.getInt("rating")),
                rs.getString("note"), rs.getString("user_name"), rs.getTimestamp("created_at").toInstant());
    }
}
