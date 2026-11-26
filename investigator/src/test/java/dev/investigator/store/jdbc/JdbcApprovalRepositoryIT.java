package dev.investigator.store.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import dev.investigator.report.Approval;
import dev.investigator.report.ApprovalRepository;
import dev.investigator.report.JdbcApprovalRepository;
import dev.investigator.testsupport.InvestigatorSpringBootTest;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The {@code postgres} approval store: request and decision are INSERTs, a second decision is a no-op (Req 7.4). */
@Tag("containers")
@Testcontainers(disabledWithoutDocker = true)
@InvestigatorSpringBootTest
@ActiveProfiles("postgres")
class JdbcApprovalRepositoryIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.create();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        PostgresContainers.register(registry, POSTGRES);
    }

    @Autowired
    ApprovalRepository approvals;
    @Autowired
    JdbcClient jdbc;

    @Test
    void decisionIsRecordedOnceAsAnInsertedRow() {
        assertThat(approvals).isInstanceOf(JdbcApprovalRepository.class);
        Instant requested = Instant.parse("2026-11-23T14:45:00Z");
        Instant decided = requested.plusSeconds(120);
        approvals.save(Approval.pending("apr-0000000000a1", "inc-0000000000a1", "#2 ROLLBACK: roll back", requested));
        assertThat(approvals.find("apr-0000000000a1")).get().extracting(Approval::status)
                .isEqualTo(Approval.Status.PENDING);

        assertThat(approvals.decide("apr-0000000000a1", Approval.Status.REJECTED, "slack:U1", decided)).isTrue();
        assertThat(approvals.decide("apr-0000000000a1", Approval.Status.APPROVED, "slack:U2", decided)).isFalse();
        assertThat(approvals.decide("apr-0000000000ff", Approval.Status.APPROVED, "slack:U2", decided)).isFalse();

        Approval current = approvals.find("apr-0000000000a1").orElseThrow();
        assertThat(current.status()).isEqualTo(Approval.Status.REJECTED);
        assertThat(current.decidedBy()).isEqualTo("slack:U1");
        assertThat(current.decidedAt()).isEqualTo(decided);
        assertThat(current.requestedAt()).isEqualTo(requested);
        assertThat(jdbc.sql("SELECT count(*) FROM approvals WHERE approval_id = 'apr-0000000000a1'")
                .query(Long.class).single()).isEqualTo(2L);
    }
}
