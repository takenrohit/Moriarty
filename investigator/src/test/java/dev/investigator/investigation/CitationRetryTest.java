package dev.investigator.investigation;

import static dev.investigator.testsupport.ScriptedChatModel.finalAnswer;
import static org.assertj.core.api.Assertions.assertThat;

import dev.investigator.domain.Incident;
import dev.investigator.domain.IncidentStatus;
import dev.investigator.domain.InvestigationReport;
import dev.investigator.domain.InvestigationStatus;
import dev.investigator.domain.Severity;
import dev.investigator.report.ReportValidator;
import dev.investigator.store.IncidentRepository;
import dev.investigator.store.ReportRepository;
import dev.investigator.testsupport.InvestigatorSpringBootTest;
import dev.investigator.testsupport.ScriptedChatModel;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A first draft citing evidence that was never stored gets one correction request carrying the
 * validation error, in the same chat memory; a corrected draft citing only stored evidence is the report
 * (Req 4.1, 4.3, 4.6).
 */
@InvestigatorSpringBootTest
class CitationRetryTest {

    private static final Instant DETECTED_AT = Instant.parse("2026-11-23T14:40:00Z");
    private static final String CORRECTION_MARKER = "failed validation with these errors";

    @Autowired InvestigationOrchestrator orchestrator;
    @Autowired ScriptedChatModel model;
    @Autowired IncidentRepository incidents;
    @Autowired ReportRepository reports;

    @BeforeEach
    void resetModel() {
        model.reset();
    }

    @Test
    void draftCitingMissingEvidenceIsCorrectedOnceAndCompletes() throws Exception {
        Incident incident = newIncident();
        String alert = "ev-" + incident.id() + "-1";
        String missing = "ev-" + incident.id() + "-99";
        String invalid = draft(incident.id(), alert, missing);
        model.enqueue(finalAnswer(invalid), finalAnswer(draft(incident.id(), alert, alert)));

        orchestrator.start(incident);

        InvestigationReport report = awaitReport(incident.id());
        assertThat(report.status()).isEqualTo(InvestigationStatus.COMPLETED);
        assertThat(report.hypotheses()).singleElement()
                .satisfies(h -> assertThat(h.supportingEvidenceIds()).containsExactly(alert));

        List<ChatRequest> requests = model.requests();
        assertThat(requests).hasSize(2);
        assertThat(requests).filteredOn(r -> lastUserText(r).contains(CORRECTION_MARKER)).hasSize(1);
        ChatRequest correction = requests.get(1);
        assertThat(lastUserText(correction)).contains(
                "- $.hypotheses[0].supportingEvidenceIds[1]: " + ReportValidator.UNKNOWN_EVIDENCE);
        // Same chat memory: the correction request still holds the first draft.
        assertThat(correction.messages()).anySatisfy(m -> assertThat(m).isInstanceOfSatisfying(AiMessage.class,
                ai -> assertThat(ai.text()).isEqualTo(invalid)));
    }

    private static String lastUserText(ChatRequest request) {
        List<ChatMessage> messages = request.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage user) {
                return user.singleText();
            }
        }
        return "";
    }

    private InvestigationReport awaitReport(String incidentId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            var report = reports.find(incidentId);
            if (report.isPresent()) {
                return report.get();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("no report for " + incidentId + " within 10 s");
    }

    private Incident newIncident() {
        byte[] bytes = new byte[6];
        ThreadLocalRandom.current().nextBytes(bytes);
        Incident incident = new Incident("inc-" + HexFormat.of().formatHex(bytes), "HighErrorRate on checkout-api",
                Severity.CRITICAL, "checkout-api", DETECTED_AT, "HighErrorRate", Map.of("env", "prod"),
                List.of(), "f".repeat(64), IncidentStatus.OPEN);
        incidents.save(incident);
        return incident;
    }

    /** A draft whose only hypothesis cites {@code alert} and {@code second}. */
    private static String draft(String incidentId, String alert, String second) {
        String supporting = alert.equals(second) ? "\"" + alert + "\"" : "\"" + alert + "\", \"" + second + "\"";
        return """
                {
                  "incidentId": "%1$s",
                  "summary": "Checkout errors rose after payment-provider timeouts.",
                  "timeline": [
                    { "at": "2026-11-23T14:40:00Z", "description": "Alert fired", "evidenceIds": ["%2$s"] }
                  ],
                  "hypotheses": [
                    { "statement": "Provider timeouts", "confidence": "MEDIUM", "supportingEvidenceIds": [%3$s],
                      "contradictingEvidenceIds": [], "establishedCause": false }
                  ],
                  "actions": [
                    { "order": 1, "type": "INVESTIGATE", "description": "Check provider status",
                      "requiresApproval": false, "evidenceIds": ["%2$s"] }
                  ],
                  "openQuestions": []
                }
                """.formatted(incidentId, alert, supporting);
    }
}
