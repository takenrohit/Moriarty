package dev.investigator.store.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import dev.investigator.domain.Evidence;
import dev.investigator.domain.Incident;
import dev.investigator.domain.Investigation;
import dev.investigator.domain.InvestigationReport;
import dev.investigator.domain.InvestigationStatus;
import dev.investigator.store.EvidenceRepository;
import dev.investigator.store.IncidentRepository;
import dev.investigator.store.InvestigationRepository;
import dev.investigator.store.ReportRepository;
import dev.investigator.testsupport.DomainGenerators;
import dev.investigator.testsupport.InvestigatorSpringBootTest;
import dev.investigator.testsupport.PropertySeeds;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Generated domain records stored through the JDBC repositories read back equal. One PostgreSQL container
 * and one Spring context serve every seed; the test is skipped when Docker is unavailable.
 */
@Tag("containers")
@Testcontainers(disabledWithoutDocker = true)
@InvestigatorSpringBootTest
@ActiveProfiles("postgres")
class PersistenceRoundTripPropertyTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.create();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        PostgresContainers.register(registry, POSTGRES);
    }

    @Autowired
    IncidentRepository incidents;
    @Autowired
    InvestigationRepository investigations;
    @Autowired
    EvidenceRepository evidence;
    @Autowired
    ReportRepository reports;

    // Feature: real-integrations, Property 10: Persistence round-trip
    // **Validates: Requirements 12.2, 12.8**
    @ParameterizedTest(name = "seed={0}")
    @MethodSource("dev.investigator.testsupport.PropertySeeds#seeds")
    void storedRecordsReadBackEqual(long seed) {
        PropertySeeds.check(seed, r -> {
            Incident incident = DomainGenerators.incident(r);
            incidents.save(incident);
            assertThat(incidents.find(incident.id())).contains(incident);

            Evidence stored = DomainGenerators.evidence(r);
            evidence.save(stored);
            assertThat(evidence.find(stored.id())).contains(stored);
            assertThat(evidence.findByIncident(stored.incidentId())).containsExactly(stored);

            InvestigationReport report = DomainGenerators.investigationReport(r);
            assertThat(reports.saveIfAbsent(report)).isTrue();
            assertThat(reports.find(report.incidentId())).contains(report);

            // A new Investigation must be PENDING; every other component is generated.
            Investigation generated = DomainGenerators.investigation(r);
            Investigation pending = new Investigation(generated.incidentId(), InvestigationStatus.PENDING,
                    generated.windowStart(), generated.windowEnd(), generated.toolCallsUsed(), generated.evidence(),
                    generated.hypotheses(), generated.timeline(), generated.recommendedActions());
            investigations.create(pending);
            assertThat(investigations.find(pending.incidentId())).contains(pending);
        });
    }
}
