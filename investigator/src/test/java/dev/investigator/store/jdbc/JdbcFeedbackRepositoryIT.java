package dev.investigator.store.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import dev.investigator.domain.InvestigationReport;
import dev.investigator.domain.InvestigationStatus;
import dev.investigator.feedback.Feedback;
import dev.investigator.feedback.FeedbackRepository;
import dev.investigator.feedback.JdbcFeedbackRepository;
import dev.investigator.store.ReportRepository;
import dev.investigator.testsupport.InvestigatorSpringBootTest;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The {@code postgres} feedback writer against the existing {@code feedback} table (Req 8.1, 8.2, 8.4). */
@Tag("containers")
@Testcontainers(disabledWithoutDocker = true)
@InvestigatorSpringBootTest
@ActiveProfiles("postgres")
class JdbcFeedbackRepositoryIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresContainers.create();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        PostgresContainers.register(registry, POSTGRES);
    }

    @Autowired
    FeedbackRepository feedback;
    @Autowired
    ReportRepository reports;

    @Test
    void feedbackIsStoredOnlyForAStoredReportAndRepeatsAreKept() {
        assertThat(feedback).isInstanceOf(JdbcFeedbackRepository.class);
        String id = "inc-0000000000fb";
        assertThat(feedback.save(id, Feedback.Rating.HELPFUL, null, null)).isEmpty();
        reports.saveIfAbsent(new InvestigationReport(id, InvestigationStatus.COMPLETED, "summary", null, null, null,
                null, null, Instant.parse("2026-11-23T14:45:00Z")));

        Feedback first = feedback.save(id, Feedback.Rating.HELPFUL, "spot on", "rohit").orElseThrow();
        feedback.save(id, Feedback.Rating.UNHELPFUL, null, null).orElseThrow();
        assertThat(first.note()).isEqualTo("spot on");
        assertThat(first.submittedBy()).isEqualTo("rohit");
        assertThat(feedback.findByIncident(id)).extracting(Feedback::rating)
                .containsExactly(Feedback.Rating.HELPFUL, Feedback.Rating.UNHELPFUL);
    }
}
