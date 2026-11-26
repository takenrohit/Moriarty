package dev.investigator.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.investigator.domain.ActionType;
import dev.investigator.domain.InvestigationReport;
import dev.investigator.domain.InvestigationStatus;
import dev.investigator.domain.RecommendedAction;
import dev.investigator.observability.InvestigatorMetrics;
import dev.investigator.report.Approval;
import dev.investigator.report.ApprovalService;
import dev.investigator.report.InMemoryApprovalRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** {@code POST /api/v1/slack/interactions}: verified decisions are recorded once and nothing runs (Req 7.2 to 7.5). */
class SlackInteractionControllerTest {

    private static final String SECRET = "test-only-signing-secret";
    private static final Instant NOW = Instant.parse("2026-11-23T15:00:00Z");
    private static final String INC = "inc-0123456789ab";

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final InMemoryApprovalRepository repository = new InMemoryApprovalRepository();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ApprovalService service = new ApprovalService(repository, clock, new InvestigatorMetrics(registry));

    private MockMvc mvc(String secret, ApprovalService approvals) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (approvals != null) {
            beans.addBean("approvalService", approvals);
        }
        return MockMvcBuilders.standaloneSetup(new SlackInteractionController(new SlackSignatureVerifier(secret, clock),
                beans.getBeanProvider(ApprovalService.class), new ObjectMapper())).build();
    }

    @Test
    void signedApproveRecordsTheDecisionOnceAndRunsNothing() throws Exception {
        Approval pending = requestOne();
        MockMvc mvc = mvc(SECRET, service);

        signed(mvc, body("approval_approve", pending.id())).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("RECORDED"));
        signed(mvc, body("approval_reject", pending.id())).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ALREADY_DECIDED"));

        Approval decided = repository.find(pending.id()).orElseThrow();
        assertThat(decided.status()).isEqualTo(Approval.Status.APPROVED);
        assertThat(decided.decidedBy()).isEqualTo("slack:U0TEST (rohit)");
        assertThat(decided.decidedAt()).isEqualTo(NOW);
        assertThat(registry.get(InvestigatorMetrics.APPROVAL_WAIT).timer().count()).isEqualTo(1);
        // The controller's only collaborators are the verifier and the approval store: recording the
        // decision is the whole effect, no recommended action is executed (Req 7.5).
    }

    @Test
    void unsignedOrStaleCallbacksGive401AndRecordNothing() throws Exception {
        Approval pending = requestOne();
        MockMvc mvc = mvc(SECRET, service);
        byte[] body = body("approval_approve", pending.id());
        String ts = Long.toString(NOW.getEpochSecond());
        String stale = Long.toString(NOW.getEpochSecond() - 301);

        mvc.perform(post(SlackInteractionController.PATH).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content(body)).andExpect(status().isUnauthorized());
        send(mvc, body, ts, SlackSignatureVerifier.sign("other-secret", ts, body)).andExpect(status().isUnauthorized());
        send(mvc, body, stale, SlackSignatureVerifier.sign(SECRET, stale, body)).andExpect(status().isUnauthorized());

        assertThat(repository.find(pending.id()).orElseThrow().status()).isEqualTo(Approval.Status.PENDING);
    }

    @Test
    void endpointIsDisabledWithoutSigningSecretOrSlack() throws Exception {
        byte[] body = body("approval_approve", "apr-000000000000");
        signed(mvc("", service), body).andExpect(status().isNotFound());
        signed(mvc(SECRET, null), body).andExpect(status().isNotFound());
    }

    private Approval requestOne() {
        InvestigationReport report = new InvestigationReport(INC, InvestigationStatus.COMPLETED, "summary", null, null,
                List.of(new RecommendedAction(1, ActionType.INVESTIGATE, "check logs", false, List.of()),
                        new RecommendedAction(2, ActionType.ROLLBACK, "roll back v2", true, List.of())),
                null, null, NOW.minusSeconds(90));
        List<Approval> approvals = service.request(report);
        assertThat(approvals).singleElement().satisfies(a -> {
            assertThat(a.id()).matches(ApprovalService.ID_PATTERN);
            assertThat(a.status()).isEqualTo(Approval.Status.PENDING);
            assertThat(a.action()).isEqualTo("#2 ROLLBACK: roll back v2");
        });
        return approvals.get(0);
    }

    private static byte[] body(String actionId, String approvalId) {
        String json = "{\"type\":\"block_actions\",\"user\":{\"id\":\"U0TEST\",\"username\":\"rohit\"},"
                + "\"actions\":[{\"action_id\":\"" + actionId + "\",\"value\":\"" + approvalId + "\"}]}";
        return ("payload=" + URLEncoder.encode(json, StandardCharsets.UTF_8)).getBytes(StandardCharsets.US_ASCII);
    }

    private static ResultActions signed(MockMvc mvc, byte[] body) throws Exception {
        String ts = Long.toString(NOW.getEpochSecond());
        return send(mvc, body, ts, SlackSignatureVerifier.sign(SECRET, ts, body));
    }

    private static ResultActions send(MockMvc mvc, byte[] body, String ts, String signature) throws Exception {
        return mvc.perform(post(SlackInteractionController.PATH).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header(SlackSignatureVerifier.TIMESTAMP_HEADER, ts)
                .header(SlackSignatureVerifier.SIGNATURE_HEADER, signature)
                .content(body));
    }
}
