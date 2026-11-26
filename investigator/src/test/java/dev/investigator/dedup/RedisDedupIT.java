package dev.investigator.dedup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.investigator.domain.Incident;
import dev.investigator.domain.IncidentStatus;
import dev.investigator.investigation.InvestigationOrchestrator;
import dev.investigator.store.IncidentRepository;
import dev.investigator.store.InvestigationRepository;
import dev.investigator.testsupport.InvestigatorSpringBootTest;
import dev.investigator.testsupport.ScriptedChatModel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The {@code redis} profile against a Redis container: 20 identical Alertmanager webhooks sent at once while
 * the first investigation is held open create one Incident and start one investigation, and the Dedup_Lock
 * key is released afterwards (Req 11.1 to 11.3, 14.2).
 */
@Tag("containers")
@Testcontainers(disabledWithoutDocker = true)
@InvestigatorSpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("redis")
class RedisDedupIT {

    private static final int REQUESTS = 20;

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.12.0-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.url",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired FingerprintLock lock;
    @Autowired StringRedisTemplate redis;
    @Autowired IncidentRepository incidents;
    @Autowired InvestigationRepository investigations;
    @Autowired ScriptedChatModel model;
    @MockitoSpyBean InvestigationOrchestrator orchestrator;

    @Test
    void concurrentDuplicatesStartOneInvestigationAndReleaseTheLock() throws Exception {
        assertThat(lock).isInstanceOf(RedisFingerprintLock.class);
        CountDownLatch release = new CountDownLatch(1);
        // Holds the first investigation RUNNING, so every duplicate meets an open Incident.
        model.reset().enqueue(request -> {
            awaitQuietly(release);
            return ScriptedChatModel.finalAnswer(ScriptedChatModel.DEFAULT_FINAL_ANSWER).apply(request);
        });
        String payload = payload(UUID.randomUUID().toString());

        List<String> ids = new ArrayList<>();
        CountDownLatch gate = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(REQUESTS)) {
            List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
            for (int i = 0; i < REQUESTS; i++) {
                futures.add(pool.submit(() -> {
                    gate.await(10, TimeUnit.SECONDS);
                    return mvc.perform(post("/api/v1/alerts/alertmanager")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload.getBytes(StandardCharsets.UTF_8))).andReturn().getResponse();
                }));
            }
            gate.countDown();
            for (Future<MockHttpServletResponse> future : futures) {
                MockHttpServletResponse response = future.get(30, TimeUnit.SECONDS);
                assertThat(response.getStatus()).isEqualTo(202);
                objectMapper.readTree(response.getContentAsString()).get("incidentIds")
                        .forEach(id -> ids.add(id.asText()));
            }
        }

        try {
            assertThat(ids).hasSize(REQUESTS).containsOnly(ids.getFirst());
            Incident incident = incidents.find(ids.getFirst()).orElseThrow();
            assertThat(incidents.findAllNewestFirst())
                    .filteredOn(i -> incident.fingerprint().equals(i.fingerprint())).hasSize(1);
            assertThat(investigations.find(incident.id())).isPresent();
            verify(orchestrator, times(1)).start(any());
            assertThat(redis.hasKey(RedisFingerprintLock.KEY_PREFIX + incident.fingerprint())).isFalse();
        } finally {
            release.countDown();
        }
        awaitReported(ids.getFirst());
    }

    private static String payload(String run) {
        return """
                { "version": "4", "status": "firing", "alerts": [
                  { "status": "firing",
                    "labels": { "alertname": "HighErrorRate", "service": "checkout-api", "test_run": "%s" },
                    "startsAt": "2026-11-23T14:40:00Z" } ] }
                """.formatted(run);
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
