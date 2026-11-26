package dev.investigator.report;

import dev.investigator.store.jdbc.PgValues;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * PostgreSQL {@link ApprovalRepository} over the append-only {@code approvals} table (Req 7.1, 7.4, 12.6).
 *
 * <p>The application role may only SELECT and INSERT there (V2__audit.sql), so a decision is a second row
 * with the same {@code approval_id} rather than an UPDATE; the latest row is the current state. The
 * partial unique indexes of V4__approval_id.sql allow one {@code PENDING} and one decision row per
 * approval, and {@code ON CONFLICT DO NOTHING} turns a concurrent second decision into a no-op.
 */
@Repository
@Profile("postgres")
public class JdbcApprovalRepository implements ApprovalRepository {

    private final JdbcClient jdbc;

    public JdbcApprovalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(Approval pending) {
        jdbc.sql("""
                INSERT INTO approvals (approval_id, incident_id, action, status, requested_at)
                VALUES (:id, :incidentId, :action, :status, CAST(:requestedAt AS timestamptz))
                """)
                .param("id", pending.id())
                .param("incidentId", PgValues.text(pending.incidentId()))
                .param("action", PgValues.text(pending.action()))
                .param("status", Approval.Status.PENDING.name())
                .param("requestedAt", PgValues.timestamp(pending.requestedAt()))
                .update();
    }

    @Override
    public Optional<Approval> find(String approvalId) {
        return jdbc.sql("""
                SELECT approval_id, incident_id, action, status, requested_at, decided_by, decided_at
                  FROM approvals WHERE approval_id = :id
                 ORDER BY id DESC LIMIT 1
                """)
                .param("id", approvalId)
                .query((rs, n) -> read(rs))
                .optional();
    }

    @Override
    public boolean decide(String approvalId, Approval.Status decision, String decidedBy, Instant decidedAt) {
        return jdbc.sql("""
                INSERT INTO approvals (approval_id, incident_id, action, status, requested_at, decided_by, decided_at)
                SELECT approval_id, incident_id, action, :status, requested_at, :decidedBy,
                       CAST(:decidedAt AS timestamptz)
                  FROM approvals WHERE approval_id = :id AND status = 'PENDING'
                ON CONFLICT DO NOTHING
                """)
                .param("id", approvalId)
                .param("status", decision.name())
                .param("decidedBy", PgValues.text(decidedBy))
                .param("decidedAt", PgValues.timestamp(decidedAt))
                .update() == 1;
    }

    private static Approval read(ResultSet rs) throws SQLException {
        return new Approval(rs.getString("approval_id"), rs.getString("incident_id"), rs.getString("action"),
                Approval.Status.valueOf(rs.getString("status")), instant(rs.getTimestamp("requested_at")),
                rs.getString("decided_by"), instant(rs.getTimestamp("decided_at")));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
