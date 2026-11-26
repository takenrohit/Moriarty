package dev.investigator.store.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.investigator.config.JacksonConfig;
import dev.investigator.domain.InvestigationReport;
import dev.investigator.store.ReportRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.Optional;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * PostgreSQL {@link ReportRepository} (Req 12.2, 9.4) over the append-only {@code reports} table. The first
 * report is version {@value #STORED_VERSION}; {@code ON CONFLICT DO NOTHING} keeps one per Incident and needs
 * only the INSERT grant. A re-investigation appends the next version with kind {@value #STORED}. Rows with
 * kind {@value #DUPLICATE} are audit rows written by {@code JdbcAuditService} (Req 12.7) and are never
 * returned by {@link #find(String)} ({@code V3__report_kind.sql}, CP-80).
 */
@Repository
@Primary
@Profile("postgres")
public class JdbcReportRepository implements ReportRepository {

    /** Version of the first stored report. */
    public static final int STORED_VERSION = 1;

    /** {@code reports.kind} of a report version {@link #find(String)} may return. */
    public static final String STORED = "stored";

    /** {@code reports.kind} of an audit row for a report produced after one was already stored. */
    public static final String DUPLICATE = "duplicate";

    private final JdbcClient jdbc;
    private final ObjectMapper codec = JacksonConfig.jsonCodec();

    public JdbcReportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean saveIfAbsent(InvestigationReport report) {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(report.incidentId(), "report.incidentId");
        return jdbc.sql("""
                INSERT INTO reports (incident_id, version, status, body, record, kind)
                VALUES (:incidentId, :version, :status, CAST(:body AS jsonb), CAST(:record AS json), :kind)
                ON CONFLICT (incident_id, version) DO NOTHING
                """)
                .param("incidentId", report.incidentId())
                .param("version", STORED_VERSION)
                .param("status", report.status() == null ? null : report.status().name())
                .param("body", PgValues.jsonb(codec, report))
                .param("record", PgValues.json(codec, report))
                .param("kind", STORED)
                .update() == 1;
    }

    @Override
    public int append(InvestigationReport report) {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(report.incidentId(), "report.incidentId");
        return jdbc.sql("""
                INSERT INTO reports (incident_id, version, status, body, record, kind)
                SELECT :incidentId, COALESCE(MAX(version), 0) + 1, :status, CAST(:body AS jsonb),
                       CAST(:record AS json), :kind
                  FROM reports WHERE incident_id = :incidentId
                RETURNING version
                """)
                .param("incidentId", report.incidentId())
                .param("status", report.status() == null ? null : report.status().name())
                .param("body", PgValues.jsonb(codec, report))
                .param("record", PgValues.json(codec, report))
                .param("kind", STORED)
                .query(Integer.class)
                .single();
    }

    @Override
    public Optional<InvestigationReport> find(String incidentId) {
        if (incidentId == null) {
            return Optional.empty();
        }
        return jdbc.sql("""
                SELECT record FROM reports WHERE incident_id = :incidentId AND kind = :kind
                 ORDER BY version DESC LIMIT 1
                """)
                .param("incidentId", incidentId)
                .param("kind", STORED)
                .query((rs, n) -> read(rs.getString("record")))
                .optional();
    }

    @Override
    public Optional<InvestigationReport> find(String incidentId, int version) {
        if (incidentId == null) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT record FROM reports WHERE incident_id = :incidentId AND version = :version")
                .param("incidentId", incidentId)
                .param("version", version)
                .query((rs, n) -> read(rs.getString("record")))
                .optional();
    }

    private InvestigationReport read(String record) {
        try {
            return codec.readValue(record, InvestigationReport.class);
        } catch (IOException e) {
            throw new UncheckedIOException("unreadable report record", e);
        }
    }
}
