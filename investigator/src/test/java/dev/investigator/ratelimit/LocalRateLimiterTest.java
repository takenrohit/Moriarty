package dev.investigator.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.investigator.alert.AlertIntakeService;
import dev.investigator.alert.AlertNormalizer;
import dev.investigator.alert.Fingerprints;
import dev.investigator.audit.NoOpAuditService;
import dev.investigator.dedup.DedupService;
import dev.investigator.dedup.LocalFingerprintLock;
import dev.investigator.domain.Incident;
import dev.investigator.domain.IncidentStatus;
import dev.investigator.domain.IncidentTimeline;
import dev.investigator.domain.Investigation;
import dev.investigator.domain.InvestigationStatus;
import dev.investigator.domain.Severity;
import dev.investigator.investigation.InvestigationOrchestrator;
import dev.investigator.store.InMemoryIncidentRepository;
import dev.investigator.store.InMemoryInvestigationRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Token-bucket examples for {@link LocalRateLimiter} and the intake path (Req 6.1, 6.2, 6.4, 6.6). */
class LocalRateLimiterTest {

    private static final Instant T0 = Instant.parse("2026-11-23T14:40:00Z");

    private final AtomicLong now = new AtomicLong();

    @Test
    void toolBucketAdmitsItsLimitThenRefillsPerMinute() {
        LocalRateLimiter limiter = new LocalRateLimiter(new RateLimitProperties(Map.of("metrics", 3), 20), now::get);

        assertThat(List.of(limiter.tryToolCall("metrics"), limiter.tryToolCall("metrics"),
                limiter.tryToolCall("metrics"), limiter.tryToolCall("metrics")))
                .containsExactly(true, true, true, false);
        // Other integrations have their own bucket with the default size.
        assertThat(limiter.tryToolCall("logs")).isTrue();

        now.addAndGet(Duration.ofSeconds(20).toNanos()); // 3 per minute: one token per 20 s
        assertThat(limiter.tryToolCall("metrics")).isTrue();
        assertThat(limiter.tryToolCall("metrics")).isFalse();

        now.addAndGet(Duration.ofHours(1).toNanos()); // refill never exceeds the limit
        assertThat(List.of(limiter.tryToolCall("metrics"), limiter.tryToolCall("metrics"),
                limiter.tryToolCall("metrics"), limiter.tryToolCall("metrics")))
                .containsExactly(true, true, true, false);
    }

    @Test
    void investigationBucketsArePerService() {
        LocalRateLimiter limiter = new LocalRateLimiter(new RateLimitProperties(Map.of(), 1), now::get);

        assertThat(limiter.tryInvestigation("checkout-api")).isTrue();
        assertThat(limiter.tryInvestigation("checkout-api")).isFalse();
        assertThat(limiter.tryInvestigation("payment-gateway")).isTrue();

        now.addAndGet(Duration.ofHours(1).toNanos());
        assertThat(limiter.tryInvestigation("checkout-api")).isTrue();
    }

    @Test
    void defaultsMatchTheDocumentedValues() {
        RateLimitProperties defaults = new RateLimitProperties(null, 20);
        assertThat(defaults.toolCallsPerMinute("metrics")).isEqualTo(RateLimitProperties.DEFAULT_TOOL_CALLS_PER_MINUTE);
    }

    @Test
    void intakeRefusesANewInvestigationOverTheLimitButAttachesDuplicatesFreely() {
        InMemoryIncidentRepository incidents = new InMemoryIncidentRepository();
        InMemoryInvestigationRepository investigations = new InMemoryInvestigationRepository();
        InvestigationOrchestrator orchestrator = mock(InvestigationOrchestrator.class);
        when(orchestrator.start(any())).thenAnswer(call -> {
            Incident incident = call.getArgument(0);
            Investigation investigation = new Investigation(incident.id(), InvestigationStatus.PENDING, T0, T0, 0,
                    List.of(), List.of(), new IncidentTimeline(List.of()), List.of());
            investigations.create(investigation);
            return investigation;
        });
        DedupService dedup = new DedupService(incidents, investigations, orchestrator, new NoOpAuditService(),
                new LocalFingerprintLock());
        AlertIntakeService intake = new AlertIntakeService(mock(AlertNormalizer.class), dedup,
                new LocalRateLimiter(new RateLimitProperties(Map.of(), 1), now::get));

        String first = intake.acceptAll("test", "p", List.of(incident("inc-000000000001", "a"))).incidentIds().get(0);
        // A repeat of the open incident attaches and takes no token.
        String repeat = intake.acceptAll("test", "p", List.of(incident("inc-000000000002", "a"))).incidentIds().get(0);
        assertThat(repeat).isEqualTo(first);

        assertThatThrownBy(() -> intake.acceptAll("test", "p", List.of(incident("inc-000000000003", "b"))))
                .isInstanceOf(InvestigationRateLimitedException.class);
        assertThat(incidents.find("inc-000000000003")).isEmpty();
        assertThat(incidents.findAllNewestFirst()).hasSize(1);
    }

    private static Incident incident(String id, String variant) {
        Map<String, String> labels = Map.of("variant", variant);
        return new Incident(id, "HighErrorRate on checkout-api", Severity.CRITICAL, "checkout-api", T0,
                "HighErrorRate", labels, List.of(), Fingerprints.of("HighErrorRate", "checkout-api", labels),
                IncidentStatus.OPEN);
    }
}
