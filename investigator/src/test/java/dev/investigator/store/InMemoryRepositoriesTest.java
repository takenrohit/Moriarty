package dev.investigator.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.investigator.domain.Evidence;
import dev.investigator.domain.EvidenceType;
import dev.investigator.domain.Incident;
import dev.investigator.domain.IncidentStatus;
import dev.investigator.domain.Investigation;
import dev.investigator.domain.InvestigationReport;
import dev.investigator.domain.InvestigationStatus;
import dev.investigator.domain.Severity;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Example tests for the in-memory stores (Req 6.1, 6.3, 6.4, 14.9). */
class InMemoryRepositoriesTest {

    private static final Instant T0 = Instant.parse("2026-11-23T14:40:00Z");

    @Test
    void transitionFollowsTheStateMachineAndRejectsStaleFrom() {
        InMemoryInvestigationRepository repo = new InMemoryInvestigationRepository();
        repo.create(investigation("inc-000000000001", InvestigationStatus.PENDING));

        assertThat(repo.transition("inc-000000000001", InvestigationStatus.PENDING, InvestigationStatus.COMPLETED))
                .isFalse();
        assertThat(repo.transition("inc-000000000001", InvestigationStatus.RUNNING, InvestigationStatus.FAILED))
                .isFalse();
        assertThat(status(repo)).isEqualTo(InvestigationStatus.PENDING);

        assertThat(repo.transition("inc-000000000001", InvestigationStatus.PENDING, InvestigationStatus.RUNNING))
                .isTrue();
        assertThat(repo.transition("inc-000000000001", InvestigationStatus.RUNNING, InvestigationStatus.COMPLETED))
                .isTrue();
        assertThat(repo.transition("inc-000000000001", InvestigationStatus.COMPLETED, InvestigationStatus.FAILED))
                .isFalse();
        assertThat(status(repo)).isEqualTo(InvestigationStatus.COMPLETED);

        assertThat(repo.transition("inc-unknown", InvestigationStatus.PENDING, InvestigationStatus.RUNNING)).isFalse();
    }

    @Test
    void createRequiresPendingAndRejectsDuplicates() {
        InMemoryInvestigationRepository repo = new InMemoryInvestigationRepository();
        assertThatThrownBy(() -> repo.create(investigation("inc-000000000001", InvestigationStatus.RUNNING)))
                .isInstanceOf(IllegalArgumentException.class);
        repo.create(investigation("inc-000000000001", InvestigationStatus.PENDING));
        assertThatThrownBy(() -> repo.create(investigation("inc-000000000001", InvestigationStatus.PENDING)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reportIsSavedOnlyOnce() {
        InMemoryReportRepository repo = new InMemoryReportRepository();
        InvestigationReport first = report(InvestigationStatus.COMPLETED);
        assertThat(repo.saveIfAbsent(first)).isTrue();
        assertThat(repo.saveIfAbsent(report(InvestigationStatus.FAILED))).isFalse();
        assertThat(repo.find("inc-000000000001")).contains(first);
    }

    @Test
    void evidenceKeepsInsertionOrderAndRejectsDuplicateIds() {
        InMemoryEvidenceRepository repo = new InMemoryEvidenceRepository();
        Evidence second = evidence("ev-inc-000000000001-2");
        Evidence first = evidence("ev-inc-000000000001-1");
        repo.save(second);
        repo.save(first);

        assertThat(repo.findByIncident("inc-000000000001")).containsExactly(second, first);
        assertThat(repo.find("ev-inc-000000000001-1")).contains(first);
        assertThat(repo.findByIncident("inc-000000000002")).isEmpty();
        assertThatThrownBy(() -> repo.save(evidence("ev-inc-000000000001-1")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(repo.findByIncident("inc-000000000001")).hasSize(2);
    }

    @Test
    void incidentsAreListedNewestFirstAndStatusIsUpdated() {
        InMemoryIncidentRepository repo = new InMemoryIncidentRepository();
        repo.save(incident("inc-000000000001", T0));
        repo.save(incident("inc-000000000002", T0.plusSeconds(60)));
        assertThatThrownBy(() -> repo.save(incident("inc-000000000001", T0)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(repo.findAllNewestFirst()).extracting(Incident::id)
                .containsExactly("inc-000000000002", "inc-000000000001");

        repo.updateStatus("inc-000000000001", IncidentStatus.INVESTIGATING);
        assertThat(repo.find("inc-000000000001")).get().extracting(Incident::status)
                .isEqualTo(IncidentStatus.INVESTIGATING);
    }

    private static InvestigationStatus status(InMemoryInvestigationRepository repo) {
        return repo.find("inc-000000000001").orElseThrow().status();
    }

    private static Investigation investigation(String incidentId, InvestigationStatus status) {
        return new Investigation(incidentId, status, T0.minusSeconds(3600), T0.plusSeconds(600), 0,
                null, null, null, null);
    }

    private static InvestigationReport report(InvestigationStatus status) {
        return new InvestigationReport("inc-000000000001", status, "summary", null, null, null, null, null, T0);
    }

    private static Evidence evidence(String id) {
        return new Evidence(id, "inc-000000000001", EvidenceType.ALERT, "alert", T0, "{}", "alert", null, "", null);
    }

    private static Incident incident(String id, Instant detectedAt) {
        return new Incident(id, "HighErrorRate on checkout-api", Severity.CRITICAL, "checkout-api", detectedAt,
                "HighErrorRate", null, null, "0".repeat(64), IncidentStatus.OPEN);
    }
}
