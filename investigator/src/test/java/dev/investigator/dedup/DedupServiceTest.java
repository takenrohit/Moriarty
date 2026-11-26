package dev.investigator.dedup;

import static org.assertj.core.api.Assertions.assertThat;

import dev.investigator.alert.Fingerprints;
import dev.investigator.domain.Incident;
import dev.investigator.domain.IncidentStatus;
import dev.investigator.domain.Severity;
import dev.investigator.store.IncidentRepository;
import dev.investigator.store.InvestigationRepository;
import dev.investigator.testsupport.InvestigatorSpringBootTest;
import dev.investigator.testsupport.ScriptedChatModel;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Example test for the Dedup_Service (Req 11.1, 11.2, 11.4, 11.5). */
@InvestigatorSpringBootTest
class DedupServiceTest {

    @Autowired DedupService dedup;
    @Autowired IncidentRepository incidents;
    @Autowired InvestigationRepository investigations;
    @Autowired ScriptedChatModel model;

    @Test
    void repeatAttachesWhileOpenAndStartsAFreshIncidentAfterTheReport() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        // Holds the first investigation RUNNING until released, so the repeat deterministically meets it open.
        model.reset().enqueue(request -> {
            awaitQuietly(release);
            return ScriptedChatModel.finalAnswer(ScriptedChatModel.DEFAULT_FINAL_ANSWER).apply(request);
        });
        Map<String, String> labels = Map.of("test-run", UUID.randomUUID().toString());

        DedupService.Admission first = dedup.admit(candidate(labels), "mock", "first");
        DedupService.Admission repeat = dedup.admit(candidate(labels), "mock", "repeat");

        assertThat(first.attached()).isFalse();
        assertThat(repeat.attached()).isTrue();
        assertThat(repeat.incidentId()).isEqualTo(first.incidentId());
        assertThat(sameFingerprint(first.incidentId())).hasSize(1);

        release.countDown();
        awaitReported(first.incidentId());

        DedupService.Admission afterReport = dedup.admit(candidate(labels), "mock", "after report");
        assertThat(afterReport.attached()).isFalse();
        assertThat(afterReport.incidentId()).isNotEqualTo(first.incidentId());
        assertThat(investigations.find(afterReport.incidentId())).isPresent();
        assertThat(sameFingerprint(first.incidentId())).hasSize(2);
        awaitReported(afterReport.incidentId());
    }

    private static Incident candidate(Map<String, String> labels) {
        String id = String.format("inc-%012x", ThreadLocalRandom.current().nextLong(1L << 48));
        return new Incident(id, "HighErrorRate on checkout-api", Severity.CRITICAL, "checkout-api",
                Instant.parse("2026-11-23T14:40:00Z"), "HighErrorRate", labels, List.of(),
                Fingerprints.of("HighErrorRate", "checkout-api", labels), IncidentStatus.OPEN);
    }

    private List<Incident> sameFingerprint(String incidentId) {
        String fingerprint = incidents.find(incidentId).orElseThrow().fingerprint();
        return incidents.findAllNewestFirst().stream().filter(i -> fingerprint.equals(i.fingerprint())).toList();
    }

    private void awaitReported(String incidentId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (incidents.find(incidentId).orElseThrow().status() != IncidentStatus.REPORTED) {
            assertThat(System.nanoTime()).as("incident %s reported in time", incidentId).isLessThan(deadline);
            Thread.sleep(10);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
