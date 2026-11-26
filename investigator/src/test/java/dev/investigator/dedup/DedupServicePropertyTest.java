package dev.investigator.dedup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.investigator.alert.Fingerprints;
import dev.investigator.audit.NoOpAuditService;
import dev.investigator.domain.Incident;
import dev.investigator.domain.IncidentStatus;
import dev.investigator.domain.IncidentTimeline;
import dev.investigator.domain.Investigation;
import dev.investigator.domain.InvestigationStatus;
import dev.investigator.domain.Severity;
import dev.investigator.investigation.InvestigationOrchestrator;
import dev.investigator.store.InMemoryIncidentRepository;
import dev.investigator.store.InMemoryInvestigationRepository;
import dev.investigator.testsupport.PropertySeeds;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Property 9 over generated sequences of alert arrivals (single or concurrent bursts), investigations moving
 * to {@code RUNNING} and investigations ending. The {@link DedupService} runs against the in-memory
 * repositories and the {@link LocalFingerprintLock}; the orchestrator is a Mockito stand-in that only creates
 * the {@code PENDING} Investigation and counts starts, so the test decides when an investigation ends.
 */
class DedupServicePropertyTest {

    private static final Instant T0 = Instant.parse("2026-11-23T14:40:00Z");

    // Feature: real-integrations, Property 9: Deduplication starts exactly one investigation per open fingerprint
    // **Validates: Requirements 11.1, 11.2, 11.6, 11.7**
    @ParameterizedTest(name = "seed={0}")
    @MethodSource("dev.investigator.testsupport.PropertySeeds#seeds")
    void oneInvestigationPerOpenFingerprint(long seed) {
        PropertySeeds.check(seed, r -> new Run(r).execute());
    }

    /** One generated case: fresh repositories, lock and orchestrator stand-in. */
    private static final class Run {

        private final SplittableRandom r;
        private final InMemoryIncidentRepository incidents = new InMemoryIncidentRepository();
        private final InMemoryInvestigationRepository investigations = new InMemoryInvestigationRepository();
        private final AtomicInteger starts = new AtomicInteger();
        private final AtomicInteger ids = new AtomicInteger();
        private final ConcurrentLinkedQueue<String> violations = new ConcurrentLinkedQueue<>();
        private final List<Map<String, String>> labelSets = new ArrayList<>();
        /** Model: the open Incident ID per fingerprint index, absent when none is open. */
        private final Map<Integer, String> expectedOpen = new HashMap<>();
        private final DedupService dedup;

        Run(SplittableRandom r) {
            this.r = r;
            int fingerprints = 1 + r.nextInt(3);
            for (int i = 0; i < fingerprints; i++) {
                labelSets.add(Map.of("alertname", "HighErrorRate", "service", "svc-" + i));
            }
            InvestigationOrchestrator orchestrator = mock(InvestigationOrchestrator.class);
            when(orchestrator.start(any())).thenAnswer(call -> fakeStart(call.getArgument(0)));
            dedup = new DedupService(incidents, investigations, orchestrator, new NoOpAuditService(),
                    new LocalFingerprintLock());
        }

        void execute() throws Exception {
            int steps = 5 + r.nextInt(20);
            for (int step = 0; step < steps; step++) {
                int op = r.nextInt(10);
                if (op < 2 && !expectedOpen.isEmpty()) {
                    finish(anyOpen());
                } else if (op < 3 && !expectedOpen.isEmpty()) {
                    markRunning(anyOpen());
                } else if (op < 6) {
                    burst();
                } else {
                    arrive(r.nextInt(labelSets.size()));
                }
                assertThat(violations).as("violations").isEmpty();
                assertAtMostOneOpenPerFingerprint();
            }
            assertThat(starts.get()).as("starts == stored incidents")
                    .isEqualTo(incidents.findAllNewestFirst().size());
        }

        /** Called under the fingerprint lock: no other open Incident may exist for this fingerprint. */
        private Investigation fakeStart(Incident incident) {
            starts.incrementAndGet();
            long othersOpen = openIncidents(incident.fingerprint()).stream()
                    .filter(id -> !id.equals(incident.id())).count();
            if (othersOpen > 0) {
                violations.add("started " + incident.id() + " while another incident was open");
            }
            Investigation investigation = new Investigation(incident.id(), InvestigationStatus.PENDING, T0, T0, 0,
                    List.of(), List.of(), new IncidentTimeline(List.of()), List.of());
            investigations.create(investigation);
            return investigation;
        }

        private void arrive(int fp) {
            DedupService.Admission admission = dedup.admit(candidate(fp), "mock", "payload");
            String open = expectedOpen.get(fp);
            if (open == null) {
                assertThat(admission.attached()).as("first alert for a closed fingerprint starts").isFalse();
                assertThat(investigations.find(admission.incidentId())).isPresent();
                expectedOpen.put(fp, admission.incidentId());
            } else {
                assertThat(admission.attached()).as("repeat alert attaches").isTrue();
                assertThat(admission.incidentId()).isEqualTo(open);
            }
        }

        /** 2 to 8 alerts over random fingerprints submitted together from several threads. */
        private void burst() throws Exception {
            int size = 2 + r.nextInt(7);
            List<Integer> fps = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                fps.add(r.nextInt(labelSets.size()));
            }
            List<Incident> candidates = fps.stream().map(this::candidate).toList();
            CountDownLatch gate = new CountDownLatch(1);
            List<DedupService.Admission> admissions = new ArrayList<>();
            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<DedupService.Admission>> futures = new ArrayList<>();
                for (Incident candidate : candidates) {
                    futures.add(pool.submit(() -> {
                        gate.await(5, TimeUnit.SECONDS);
                        return dedup.admit(candidate, "mock", "payload");
                    }));
                }
                gate.countDown();
                for (Future<DedupService.Admission> future : futures) {
                    admissions.add(future.get(5, TimeUnit.SECONDS));
                }
            }
            for (int fp : new TreeSet<>(fps)) {
                List<DedupService.Admission> forFp = new ArrayList<>();
                for (int i = 0; i < size; i++) {
                    if (fps.get(i) == fp) {
                        forFp.add(admissions.get(i));
                    }
                }
                List<DedupService.Admission> started = forFp.stream().filter(a -> !a.attached()).toList();
                String open = expectedOpen.get(fp);
                if (open == null) {
                    assertThat(started).as("exactly one alert of the burst starts").hasSize(1);
                    open = started.getFirst().incidentId();
                    expectedOpen.put(fp, open);
                } else {
                    assertThat(started).as("no alert of the burst starts while open").isEmpty();
                }
                String expected = open;
                assertThat(forFp).allSatisfy(a -> assertThat(a.incidentId()).isEqualTo(expected));
            }
        }

        private void markRunning(int fp) {
            String id = expectedOpen.get(fp);
            if (investigations.transition(id, InvestigationStatus.PENDING, InvestigationStatus.RUNNING)) {
                incidents.updateStatus(id, IncidentStatus.INVESTIGATING);
            }
        }

        /**
         * Ends the open investigation. Usually the Incident becomes {@code REPORTED}; sometimes it is left in
         * the gap where the Investigation has ended but the Incident is not yet {@code REPORTED}.
         */
        private void finish(int fp) {
            markRunning(fp);
            String id = expectedOpen.remove(fp);
            InvestigationStatus end = switch (r.nextInt(3)) {
                case 0 -> InvestigationStatus.COMPLETED;
                case 1 -> InvestigationStatus.INCONCLUSIVE;
                default -> InvestigationStatus.FAILED;
            };
            assertThat(investigations.transition(id, InvestigationStatus.RUNNING, end)).isTrue();
            if (r.nextInt(4) > 0) {
                incidents.updateStatus(id, IncidentStatus.REPORTED);
            }
        }

        private int anyOpen() {
            List<Integer> open = new ArrayList<>(new TreeSet<>(expectedOpen.keySet()));
            return open.get(r.nextInt(open.size()));
        }

        private void assertAtMostOneOpenPerFingerprint() {
            for (int fp = 0; fp < labelSets.size(); fp++) {
                List<String> open = openIncidents(fingerprint(fp));
                String expected = expectedOpen.get(fp);
                assertThat(open).as("open incidents for fingerprint %d", fp)
                        .isEqualTo(expected == null ? List.of() : List.of(expected));
            }
        }

        /** Open = Incident OPEN/INVESTIGATING with its Investigation PENDING/RUNNING. */
        private List<String> openIncidents(String fingerprint) {
            return incidents.findAllNewestFirst().stream()
                    .filter(i -> fingerprint.equals(i.fingerprint()))
                    .filter(i -> i.status() == IncidentStatus.OPEN || i.status() == IncidentStatus.INVESTIGATING)
                    .filter(i -> investigations.find(i.id()).map(Investigation::status)
                            .filter(s -> s == InvestigationStatus.PENDING || s == InvestigationStatus.RUNNING)
                            .isPresent())
                    .map(Incident::id)
                    .toList();
        }

        private String fingerprint(int fp) {
            return Fingerprints.of("HighErrorRate", "svc-" + fp, labelSets.get(fp));
        }

        private Incident candidate(int fp) {
            String id = String.format("inc-%012x", ids.incrementAndGet());
            return new Incident(id, "HighErrorRate on svc-" + fp, Severity.CRITICAL, "svc-" + fp, T0,
                    "HighErrorRate", labelSets.get(fp), List.of(), fingerprint(fp), IncidentStatus.OPEN);
        }
    }
}
