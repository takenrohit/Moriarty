package dev.investigator.store.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.investigator.config.JacksonConfig;
import dev.investigator.domain.Incident;
import dev.investigator.domain.IncidentStatus;
import dev.investigator.store.IncidentRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * PostgreSQL {@link IncidentRepository} (Req 12.2). The Incident is read from the exact {@code record}
 * JSON; the {@code status} column, changed by {@link #updateStatus}, overrides the status stored in it.
 */
@Repository
@Primary
@Profile("postgres")
public class JdbcIncidentRepository implements IncidentRepository {

    /** Same order as the in-memory repository; sorted in Java because timestamptz drops nanoseconds. */
    private static final Comparator<Incident> NEWEST_FIRST = Comparator
            .comparing(Incident::detectedAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(Incident::id);

    private final JdbcClient jdbc;
    private final ObjectMapper codec = JacksonConfig.jsonCodec();

    public JdbcIncidentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(Incident incident) {
        Objects.requireNonNull(incident, "incident");
        Objects.requireNonNull(incident.id(), "incident.id");
        Objects.requireNonNull(incident.status(), "incident.status");
        int inserted = jdbc.sql("""
                INSERT INTO incidents (id, fingerprint, service, severity, alert_name, labels, detected_at, status,
                                       record)
                VALUES (:id, :fingerprint, :service, :severity, :alertName, CAST(:labels AS jsonb),
                        CAST(:detectedAt AS timestamptz), :status, CAST(:record AS json))
                ON CONFLICT (id) DO NOTHING
                """)
                .param("id", incident.id())
                .param("fingerprint", PgValues.text(incident.fingerprint()))
                .param("service", PgValues.text(incident.affectedService()))
                .param("severity", incident.severity() == null ? null : incident.severity().name())
                .param("alertName", PgValues.text(incident.alertName()))
                .param("labels", PgValues.jsonb(codec, incident.labels()))
                .param("detectedAt", PgValues.timestamp(incident.detectedAt()))
                .param("status", incident.status().name())
                .param("record", PgValues.json(codec, incident))
                .update();
        if (inserted == 0) {
            throw new IllegalStateException("incident already stored: " + incident.id());
        }
    }

    @Override
    public Optional<Incident> find(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT record, status FROM incidents WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> read(rs.getString("record"), rs.getString("status")))
                .optional();
    }

    @Override
    public List<Incident> findAllNewestFirst() {
        return jdbc.sql("SELECT record, status FROM incidents")
                .query((rs, n) -> read(rs.getString("record"), rs.getString("status")))
                .list().stream().sorted(NEWEST_FIRST).toList();
    }

    @Override
    public void updateStatus(String id, IncidentStatus status) {
        Objects.requireNonNull(status, "status");
        int updated = jdbc.sql("UPDATE incidents SET status = :status WHERE id = :id")
                .param("status", status.name())
                .param("id", id)
                .update();
        if (updated == 0) {
            throw new NoSuchElementException("no incident " + id);
        }
    }

    private Incident read(String record, String status) {
        try {
            Incident stored = codec.readValue(record, Incident.class);
            return new Incident(stored.id(), stored.title(), stored.severity(), stored.affectedService(),
                    stored.detectedAt(), stored.alertName(), stored.labels(), stored.dashboardLinks(),
                    stored.fingerprint(), IncidentStatus.valueOf(status));
        } catch (IOException e) {
            throw new UncheckedIOException("unreadable incident record", e);
        }
    }
}
