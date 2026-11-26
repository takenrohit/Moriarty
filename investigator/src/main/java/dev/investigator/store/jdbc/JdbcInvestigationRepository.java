package dev.investigator.store.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.investigator.config.JacksonConfig;
import dev.investigator.domain.Investigation;
import dev.investigator.domain.InvestigationStatus;
import dev.investigator.investigation.StatusTransitions;
import dev.investigator.store.InvestigationRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * PostgreSQL {@link InvestigationRepository} (Req 12.2). The {@code status} and {@code tool_calls_used}
 * columns override the values in the {@code record} JSON written at creation. {@link #transition} is a
 * single {@code UPDATE ... WHERE status = :from}, so concurrent callers cannot both leave a status.
 */
@Repository
@Primary
@Profile("postgres")
public class JdbcInvestigationRepository implements InvestigationRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper codec = JacksonConfig.jsonCodec();

    public JdbcInvestigationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void create(Investigation investigation) {
        Objects.requireNonNull(investigation, "investigation");
        Objects.requireNonNull(investigation.incidentId(), "investigation.incidentId");
        if (investigation.status() != InvestigationStatus.PENDING) {
            throw new IllegalArgumentException("a new investigation must be PENDING, was " + investigation.status());
        }
        int inserted = jdbc.sql("""
                INSERT INTO investigations (incident_id, status, window_start, window_end, tool_calls_used, record)
                VALUES (:incidentId, :status, CAST(:windowStart AS timestamptz), CAST(:windowEnd AS timestamptz),
                        :toolCallsUsed, CAST(:record AS json))
                ON CONFLICT (incident_id) DO NOTHING
                """)
                .param("incidentId", investigation.incidentId())
                .param("status", investigation.status().name())
                .param("windowStart", PgValues.timestamp(investigation.windowStart()))
                .param("windowEnd", PgValues.timestamp(investigation.windowEnd()))
                .param("toolCallsUsed", investigation.toolCallsUsed())
                .param("record", PgValues.json(codec, investigation))
                .update();
        if (inserted == 0) {
            throw new IllegalStateException("investigation already exists for " + investigation.incidentId());
        }
    }

    @Override
    public Optional<Investigation> find(String incidentId) {
        if (incidentId == null) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT record, status, tool_calls_used FROM investigations WHERE incident_id = :incidentId")
                .param("incidentId", incidentId)
                .query((rs, n) -> read(rs.getString("record"), rs.getString("status"), rs.getInt("tool_calls_used")))
                .optional();
    }

    @Override
    public boolean transition(String incidentId, InvestigationStatus from, InvestigationStatus to) {
        if (incidentId == null || !StatusTransitions.isAllowed(from, to)) {
            return false;
        }
        boolean running = to == InvestigationStatus.RUNNING;
        int updated = jdbc.sql("""
                UPDATE investigations
                   SET status = :to,
                       started_at = CASE WHEN :running THEN now() ELSE started_at END,
                       finished_at = CASE WHEN :running THEN finished_at ELSE now() END
                 WHERE incident_id = :incidentId AND status = :from
                """)
                .param("to", to.name())
                .param("running", running)
                .param("incidentId", incidentId)
                .param("from", from.name())
                .update();
        return updated == 1;
    }

    @Override
    public void recordToolCalls(String incidentId, int used) {
        if (used < 0) {
            throw new IllegalArgumentException("used must not be negative: " + used);
        }
        int updated = incidentId == null ? 0 : jdbc.sql(
                        "UPDATE investigations SET tool_calls_used = :used WHERE incident_id = :incidentId")
                .param("used", used)
                .param("incidentId", incidentId)
                .update();
        if (updated == 0) {
            throw new NoSuchElementException("no investigation for " + incidentId);
        }
    }

    private Investigation read(String record, String status, int toolCallsUsed) {
        try {
            Investigation stored = codec.readValue(record, Investigation.class);
            return new Investigation(stored.incidentId(), InvestigationStatus.valueOf(status), stored.windowStart(),
                    stored.windowEnd(), toolCallsUsed, stored.evidence(), stored.hypotheses(), stored.timeline(),
                    stored.recommendedActions());
        } catch (IOException e) {
            throw new UncheckedIOException("unreadable investigation record", e);
        }
    }
}
