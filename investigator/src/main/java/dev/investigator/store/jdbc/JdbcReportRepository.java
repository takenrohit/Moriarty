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
 * PostgreSQL {@link ReportRepository} (Req 12.2). The stored report is version {@value #STORED_VERSION} of
 * the append-only {@code reports} table; {@code ON CONFLICT DO NOTHING} keeps one per Incident and needs
 * only the INSERT grant. Later versions are audit rows written by {@code JdbcAuditService} (Req 12.7).
 */
@Repository
@Primary
@Profile("postgres")
public class JdbcReportRepository implements ReportRepository {

    /** Version of the report returned by {@link #find}. */
    public static final int STORED_VERSION = 1;

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
                INSERT INTO reports (incident_id, version, status, body, record)
                VALUES (:incidentId, :version, :status, CAST(:body AS jsonb), CAST(:record AS json))
                ON CONFLICT (incident_id, version) DO NOTHING
                """)
                .param("incidentId", report.incidentId())
                .param("version", STORED_VERSION)
                .param("status", report.status() == null ? null : report.status().name())
                .param("body", PgValues.jsonb(codec, report))
                .param("record", PgValues.json(codec, report))
                .update() == 1;
    }

    @Override
    public Optional<InvestigationReport> find(String incidentId) {
        if (incidentId == null) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT record FROM reports WHERE incident_id = :incidentId AND version = :version")
                .param("incidentId", incidentId)
                .param("version", STORED_VERSION)
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
