package dev.investigator.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.investigator.domain.Evidence;
import dev.investigator.domain.EvidenceRef;
import dev.investigator.domain.EvidenceType;
import dev.investigator.domain.Incident;
import dev.investigator.domain.IncidentStatus;
import dev.investigator.domain.Severity;
import dev.investigator.integration.metrics.MetricSeries;
import dev.investigator.integration.mock.MockScenario;
import dev.investigator.investigation.InvestigationContext;
import dev.investigator.store.EvidenceRepository;
import dev.investigator.testsupport.InvestigatorSpringBootTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@InvestigatorSpringBootTest
class EvidenceServiceTest {

    private static final Instant A = Instant.parse("2026-11-23T14:40:00Z");
    private static final String ERROR_RATIO_QUERY =
            "sum(rate(http_requests_total{service=\"checkout-api\",status=~\"5..\"}[5m]))"
                    + " / sum(rate(http_requests_total{service=\"checkout-api\"}[5m]))";

    @Autowired
    EvidenceService evidenceService;

    @Autowired
    EvidenceRepository repository;

    @Autowired
    MetricSummarizer metricSummarizer;

    @Autowired
    ObjectMapper mapper;

    @Test
    void alertGetsIdOneAndToolEvidenceFollows() throws Exception {
        Incident incident = incident();
        Evidence alert = evidenceService.storeAlert(incident, "0".repeat(32));

        assertThat(alert.id()).isEqualTo("ev-" + incident.id() + "-1");
        assertThat(alert.type()).isEqualTo(EvidenceType.ALERT);
        assertThat(alert.source()).isEqualTo("alert-intake");
        assertThat(alert.observedAt()).isEqualTo(incident.detectedAt());
        assertThat(alert.citation()).isEqualTo("https://grafana.example/d/checkout");
        assertThat(alert.redactions()).isEmpty();
        assertThat(alert.headline()).isEqualTo("HighErrorRate fired on checkout-api (CRITICAL)");
        JsonNode alertJson = mapper.readTree(alert.summary());
        assertThat(alertJson.get("evidenceId").asText()).isEqualTo(alert.id());
        assertThat(alertJson.get("severity").asText()).isEqualTo("CRITICAL");
        assertThat(alertJson.has("headline")).isFalse();

        MetricSeries series = MockScenario.forAnchor(A).metric("checkout-api", ERROR_RATIO_QUERY,
                A.minus(Duration.ofMinutes(60)), A.plus(Duration.ofMinutes(10)), Duration.ofMinutes(1), 300);
        Evidence metric = evidenceService.store(context(incident), EvidenceType.METRIC, "prometheus", A,
                ERROR_RATIO_QUERY, id -> metricSummarizer.summarize(id, "checkout-api", ERROR_RATIO_QUERY,
                        A.minus(Duration.ofMinutes(60)), A.plus(Duration.ofMinutes(10)), false, series))
                .evidence();

        assertThat(metric.id()).isEqualTo("ev-" + incident.id() + "-2");
        JsonNode json = mapper.readTree(metric.summary());
        assertThat(json.get("evidenceId").asText()).isEqualTo(metric.id());
        assertThat(json.get("step").asText()).isEqualTo("PT1M");
        assertThat(json.get("changePointAt").asText()).isEqualTo("2026-11-23T14:38:00Z");
        assertThat(json.get("baseline").asDouble()).isEqualTo(0.002);
        assertThat(json.get("valueAfterChange").asDouble()).isEqualTo(0.12);
        assertThat(json.has("headline")).isFalse();
        assertThat(metric.payload()).containsEntry("evidenceId", metric.id());
        assertThat(metric.headline()).isEqualTo("checkout-api metric changed from 0.002 to 0.12 at 2026-11-23T14:38:00Z");

        List<EvidenceRef> refs = evidenceService.refs(incident.id());
        assertThat(refs).extracting(EvidenceRef::id).containsExactly(alert.id(), metric.id());
        assertThat(refs.get(1)).isEqualTo(new EvidenceRef(metric.id(), EvidenceType.METRIC, "prometheus", A,
                metric.headline(), ERROR_RATIO_QUERY));
        assertThat(repository.find(metric.id())).contains(metric);
    }

    @Test
    void failedStoreLeavesNoGapAndMismatchedIdIsRejected() {
        Incident incident = incident();
        InvestigationContext ctx = context(incident);
        evidenceService.storeAlert(incident, "0".repeat(32));

        assertThatThrownBy(() -> evidenceService.store(ctx, EvidenceType.LOG, "loki", A, "q", id -> {
            throw new IllegalStateException("summarizer failed");
        })).hasMessage("summarizer failed");
        assertThatThrownBy(() -> evidenceService.store(ctx, EvidenceType.LOG, "loki", A, "q",
                id -> RunbookSummary.of("ev-other-1", "t", "s", List.of())))
                .isInstanceOf(IllegalStateException.class);

        StoredEvidence stored = evidenceService.store(ctx, EvidenceType.RUNBOOK, "runbooks", A, "runbooks/x.md",
                id -> RunbookSummary.of(id, "Payment gateway degradation", "payment-gateway", List.of("Check")));
        Evidence next = stored.evidence();
        assertThat(stored.redactionStatus()).isEqualTo(RedactionResult.NOT_APPLIED);
        assertThat(next.id()).isEqualTo("ev-" + incident.id() + "-2");
        assertThat(repository.findByIncident(incident.id())).hasSize(2);
    }

    private static Incident incident() {
        String id = "inc-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return new Incident(id, "HighErrorRate on checkout-api", Severity.CRITICAL, "checkout-api", A,
                "HighErrorRate", Map.of("team", "payments"), List.of("https://grafana.example/d/checkout"),
                "f".repeat(64), IncidentStatus.OPEN);
    }

    private static InvestigationContext context(Incident incident) {
        return new InvestigationContext(incident.id(), A.minus(Duration.ofMinutes(60)), A.plus(Duration.ofMinutes(10)),
                A.plus(Duration.ofMinutes(3)), "0".repeat(32), "ev-" + incident.id() + "-1", A, 15);
    }
}
