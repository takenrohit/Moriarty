package dev.investigator.store.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.investigator.config.JacksonConfig;
import dev.investigator.domain.Evidence;
import dev.investigator.store.EvidenceRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * PostgreSQL {@link EvidenceRepository} (Req 12.2). Insertion order is the identity column {@code seq};
 * the ID is unique, so a duplicate insert changes nothing and is reported as an error.
 */
@Repository
@Primary
@Profile("postgres")
public class JdbcEvidenceRepository implements EvidenceRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper codec = JacksonConfig.jsonCodec();

    public JdbcEvidenceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(Evidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(evidence.id(), "evidence.id");
        Objects.requireNonNull(evidence.incidentId(), "evidence.incidentId");
        int inserted = jdbc.sql("""
                INSERT INTO evidence (id, incident_id, type, source, observed_at, summary, headline, payload,
                                      citation, redactions, record)
                VALUES (:id, :incidentId, :type, :source, CAST(:observedAt AS timestamptz), :summary, :headline,
                        CAST(:payload AS jsonb), :citation, CAST(:redactions AS jsonb), CAST(:record AS json))
                ON CONFLICT (id) DO NOTHING
                """)
                .param("id", evidence.id())
                .param("incidentId", evidence.incidentId())
                .param("type", evidence.type() == null ? null : evidence.type().name())
                .param("source", PgValues.text(evidence.source()))
                .param("observedAt", PgValues.timestamp(evidence.observedAt()))
                .param("summary", PgValues.text(evidence.summary()))
                .param("headline", PgValues.text(evidence.headline()))
                .param("payload", PgValues.jsonb(codec, evidence.payload()))
                .param("citation", PgValues.text(evidence.citation()))
                .param("redactions", PgValues.jsonb(codec, evidence.redactions()))
                .param("record", PgValues.json(codec, evidence))
                .update();
        if (inserted == 0) {
            throw new IllegalStateException("evidence already stored: " + evidence.id());
        }
    }

    @Override
    public List<Evidence> findByIncident(String incidentId) {
        if (incidentId == null) {
            return List.of();
        }
        return jdbc.sql("SELECT record FROM evidence WHERE incident_id = :incidentId ORDER BY seq")
                .param("incidentId", incidentId)
                .query((rs, n) -> read(rs.getString("record")))
                .list();
    }

    @Override
    public Optional<Evidence> find(String evidenceId) {
        if (evidenceId == null) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT record FROM evidence WHERE id = :id")
                .param("id", evidenceId)
                .query((rs, n) -> read(rs.getString("record")))
                .optional();
    }

    private Evidence read(String record) {
        try {
            return codec.readValue(record, Evidence.class);
        } catch (IOException e) {
            throw new UncheckedIOException("unreadable evidence record", e);
        }
    }
}
