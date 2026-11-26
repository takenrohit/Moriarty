package dev.investigator.store.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.investigator.audit.AuditService;
import dev.investigator.audit.JdbcAuditService;
import dev.investigator.domain.ActionType;
import dev.investigator.domain.Confidence;
import dev.investigator.domain.Evidence;
import dev.investigator.domain.EvidenceRef;
import dev.investigator.domain.EvidenceType;
import dev.investigator.domain.Hypothesis;
import dev.investigator.domain.Incident;
import dev.investigator.domain.IncidentStatus;
import dev.investigator.domain.IncidentTimeline;
import dev.investigator.domain.Investigation;
import dev.investigator.domain.InvestigationReport;
import dev.investigator.domain.InvestigationStatus;
import dev.investigator.domain.RecommendedAction;
import dev.investigator.domain.Severity;
import dev.investigator.domain.TimelineEntry;
import dev.investigator.store.EvidenceRepository;
import dev.investigator.store.IncidentRepository;
import dev.investigator.store.InvestigationRepository;
import dev.investigator.store.ReportRepository;
import dev.investigator.testsupport.InvestigatorSpringBootTest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The {@code postgres} profile against a PostgreSQL container: Flyway migrates before the context serves
 * requests, the JDBC repositories replace the in-memory ones and round-trip an Incident, its Evidence and its
 * report, and the application role cannot UPDATE or DELETE audit rows (Req 12.1 to 12.7, 14.1, 14.5, 14.7).
 */
@Tag("containers")
@Testcontainers(disabledWithoutDocker = true)
@InvestigatorSpringBootTest
@ActiveProfiles("postgres")
class PostgresPersistenceIT {

    private static final Instant T0 = Instant.parse("2026-11-23T14:40:00.123456789Z");
    private static final List<String> AUDIT_TABLES =
            List.of("alerts_raw", "tool_calls", "llm_exchanges", "reports", "approvals");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.create();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        PostgresContainers.register(registry, POSTGRES);
    }

    @Autowired
    Flyway flyway;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    IncidentRepository incidents;
    @Autowired
    InvestigationRepository investigations;
    @Autowired
    EvidenceRepository evidence;
    @Autowired
    ReportRepository reports;
    @Autowired
    AuditService audit;

    @Test
    void flywayMigratedAndTheAppConnectsAsTheNonOwnerRole() {
        assertThat(flyway.info().applied()).extracting(MigrationInfo::getVersion)
                .extracting(Object::toString).containsExactly("1", "2", "3", "4");
        assertThat(jdbc.sql("SELECT current_user").query(String.class).single()).isEqualTo(PostgresContainers.APP_ROLE);
        assertThat(jdbc.sql("SELECT rolsuper FROM pg_roles WHERE rolname = current_user").query(Boolean.class)
                .single()).isFalse();
        assertThat(incidents).isInstanceOf(JdbcIncidentRepository.class);
        assertThat(investigations).isInstanceOf(JdbcInvestigationRepository.class);
        assertThat(evidence).isInstanceOf(JdbcEvidenceRepository.class);
        assertThat(reports).isInstanceOf(JdbcReportRepository.class);
        assertThat(audit).isInstanceOf(JdbcAuditService.class);
    }

    @Test
    void incidentEvidenceAndReportRoundTrip() {
        String id = "inc-a1b2c3d4e5f6";
        Incident incident = new Incident(id, "High error rate on checkout-api", Severity.CRITICAL, "checkout-api", T0,
                "HighErrorRate", Map.of("service", "checkout-api", "env", "nul\u0000demo"),
                List.of("https://grafana.example.com/d/abc"), "f".repeat(64), IncidentStatus.OPEN);
        incidents.save(incident);
        assertThat(incidents.find(id)).contains(incident);
        assertThatThrownBy(() -> incidents.save(incident)).isInstanceOf(IllegalStateException.class);
        incidents.updateStatus(id, IncidentStatus.INVESTIGATING);
        assertThat(incidents.find(id)).map(Incident::status).contains(IncidentStatus.INVESTIGATING);

        investigations.create(new Investigation(id, InvestigationStatus.PENDING, T0.minusSeconds(3600),
                T0.plusSeconds(600), 0, List.of(), List.of(), new IncidentTimeline(List.of()), List.of()));
        assertThat(investigations.transition(id, InvestigationStatus.PENDING, InvestigationStatus.COMPLETED)).isFalse();
        assertThat(investigations.transition(id, InvestigationStatus.PENDING, InvestigationStatus.RUNNING)).isTrue();
        assertThat(investigations.transition(id, InvestigationStatus.PENDING, InvestigationStatus.RUNNING)).isFalse();
        investigations.recordToolCalls(id, 3);
        assertThat(investigations.find(id)).hasValueSatisfying(found -> {
            assertThat(found.status()).isEqualTo(InvestigationStatus.RUNNING);
            assertThat(found.toolCallsUsed()).isEqualTo(3);
            assertThat(found.windowStart()).isEqualTo(T0.minusSeconds(3600));
        });

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("errorRate", 0.12);
        payload.put("count", 1234567890123L);
        payload.put("negativeZero", -0.0);
        payload.put("samples", List.of(1, 2, 3));
        Evidence alert = new Evidence("ev-" + id + "-1", id, EvidenceType.ALERT, "alert", T0, "{\"alert\":true}",
                "HighErrorRate fired", payload, "alert HighErrorRate", Set.of("not_applied"));
        Evidence metric = new Evidence("ev-" + id + "-2", id, EvidenceType.METRIC, "metrics", T0.plusNanos(1),
                "{\"matches\":2}", "5xx ratio rose to 12%", Map.of(), "PromQL ...", Set.of());
        evidence.save(alert);
        evidence.save(metric);
        assertThat(evidence.findByIncident(id)).containsExactly(alert, metric);
        assertThat(evidence.find(metric.id())).contains(metric);
        assertThatThrownBy(() -> evidence.save(alert)).isInstanceOf(IllegalStateException.class);

        InvestigationReport report = report(id, InvestigationStatus.COMPLETED, alert);
        assertThat(reports.saveIfAbsent(report)).isTrue();
        InvestigationReport late = report(id, InvestigationStatus.FAILED, alert);
        assertThat(reports.saveIfAbsent(late)).isFalse();
        audit.recordReport(late);
        assertThat(reports.find(id)).contains(report);
        assertThat(jdbc.sql("SELECT version FROM reports WHERE incident_id = :id ORDER BY version")
                .param("id", id).query(Integer.class).list()).containsExactly(1, 2);

        // A re-investigation appends the next version, which becomes the current report (Req 9.4).
        InvestigationReport rerun = report(id, InvestigationStatus.INCONCLUSIVE, alert);
        assertThat(reports.append(rerun)).isEqualTo(3);
        assertThat(reports.find(id)).contains(rerun);
        assertThat(reports.find(id, 1)).contains(report);
        assertThat(investigations.restart(new Investigation(id, InvestigationStatus.PENDING, T0, T0, 0, List.of(),
                List.of(), new IncidentTimeline(List.of()), List.of()))).isFalse();
        assertThat(investigations.transition(id, InvestigationStatus.RUNNING, InvestigationStatus.COMPLETED)).isTrue();
        assertThat(investigations.restart(new Investigation(id, InvestigationStatus.PENDING, T0, T0, 0, List.of(),
                List.of(), new IncidentTimeline(List.of()), List.of()))).isTrue();
        assertThat(investigations.find(id)).map(Investigation::status).contains(InvestigationStatus.PENDING);
    }

    @Test
    void auditRowsAreInsertedButCannotBeUpdatedOrDeleted() {
        String id = "inc-0000000000aa";
        Incident incident = new Incident(id, "t", Severity.WARNING, "checkout-api", T0, "A", Map.of(), List.of(),
                "a".repeat(64), IncidentStatus.OPEN);
        audit.recordAlert("alertmanager", Map.of("status", "firing", "text", "nul\u0000inside"), incident);
        audit.recordToolCall(new AuditService.ToolCall(id, "getMetric", Map.of("service", "checkout-api"), "success",
                12, List.of("ev-" + id + "-2"), null));
        audit.recordLlmExchange(new AuditService.LlmExchange(id, "gpt-4o-mini",
                "{\"messages\":[{\"text\":\"a\\u0000b\"}]}", "{\"text\":\"ok\"}", 100, 20, 850));
        audit.recordReport(report(id, InvestigationStatus.INCONCLUSIVE, null));
        audit.recordApproval(new AuditService.Approval(id, "ROLLBACK", "PENDING", T0, null, null));

        for (String table : AUDIT_TABLES) {
            assertThat(count(table, id)).as(table).isEqualTo(1);
            assertThatThrownBy(() -> jdbc.sql("UPDATE " + table + " SET incident_id = 'x' WHERE incident_id = :id")
                    .param("id", id).update())
                    .as("UPDATE " + table).isInstanceOf(DataAccessException.class)
                    .rootCause().hasMessageContaining("permission denied");
            assertThatThrownBy(() -> jdbc.sql("DELETE FROM " + table + " WHERE incident_id = :id")
                    .param("id", id).update())
                    .as("DELETE " + table).isInstanceOf(DataAccessException.class)
                    .rootCause().hasMessageContaining("permission denied");
            assertThat(count(table, id)).as(table).isEqualTo(1);
        }
    }

    private int count(String table, String incidentId) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE incident_id = :id")
                .param("id", incidentId).query(Integer.class).single();
    }

    private static InvestigationReport report(String id, InvestigationStatus status, Evidence cited) {
        List<String> ids = cited == null ? List.of() : List.of(cited.id());
        List<EvidenceRef> refs = cited == null ? List.of() : List.of(new EvidenceRef(cited.id(), cited.type(),
                cited.source(), cited.observedAt(), cited.headline(), cited.citation()));
        return new InvestigationReport(id, status, "Errors rose after the deploy.",
                new IncidentTimeline(List.of(new TimelineEntry(T0, "alert fired", ids))),
                List.of(new Hypothesis("bad deploy", Confidence.HIGH, ids, List.of(), true)),
                List.of(new RecommendedAction(1, ActionType.ROLLBACK, "roll back", true, ids)),
                List.of("is the provider healthy?"), refs, T0.plusSeconds(90));
    }
}
