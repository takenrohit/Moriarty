package dev.investigator.report;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.investigator.config.PropertiesConfig;
import dev.investigator.domain.ActionType;
import dev.investigator.domain.Confidence;
import dev.investigator.domain.EvidenceRef;
import dev.investigator.domain.EvidenceType;
import dev.investigator.domain.Hypothesis;
import dev.investigator.domain.Incident;
import dev.investigator.domain.IncidentStatus;
import dev.investigator.domain.IncidentTimeline;
import dev.investigator.domain.InvestigationReport;
import dev.investigator.domain.InvestigationStatus;
import dev.investigator.domain.RecommendedAction;
import dev.investigator.domain.Severity;
import dev.investigator.domain.TimelineEntry;
import dev.investigator.store.InMemoryReportRepository;
import dev.investigator.store.ReportRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** Slack_Channel against a WireMock Slack API (Req 13.1 to 13.6, 15.4). */
@ExtendWith(OutputCaptureExtension.class)
class SlackReportChannelTest {

    private static final WireMockServer SLACK = new WireMockServer(options().dynamicPort());
    private static final String TOKEN = "xoxb-test-only-0123456789-abcdef";
    private static final String CHANNEL = "C0TESTCHAN";
    private static final String INC = "inc-0123456789ab";
    private static final Instant T0 = Instant.parse("2026-11-23T14:40:00Z");

    static {
        SLACK.start();
    }

    @AfterAll
    static void stop() {
        SLACK.stop();
    }

    @BeforeEach
    void reset() {
        SLACK.resetAll();
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class, ReportRenderer.class, LogReportChannel.class,
                    SlackBlockKitRenderer.class, SlackReportChannel.class, ReportPublisher.class,
                    InMemoryReportRepository.class);

    private ApplicationContextRunner slack() {
        return runner.withPropertyValues("investigator.slack.bot-token=" + TOKEN,
                "investigator.slack.channel-id=" + CHANNEL,
                "investigator.slack.api-url=" + SLACK.baseUrl() + "/api",
                "investigator.public-base-url=https://investigator.example/");
    }

    @Test
    void unsetSlackConfigLeavesOnlyTheLogChannel(CapturedOutput output) {
        runner.withPropertyValues("investigator.slack.bot-token=", "investigator.slack.channel-id=").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(SlackReportChannel.class);
            ReportPublisher publisher = ctx.getBean(ReportPublisher.class);
            assertThat(publisher.channelNames()).containsExactly("log");

            publisher.publish(incident(), report());

            assertThat(output).contains("investigation report incident_id=" + INC);
            assertThat(SLACK.getAllServeEvents()).isEmpty();
        });
    }

    @Test
    void tokenWithoutChannelFailsStartupNamingTheMissingVariable() {
        runner.withPropertyValues("investigator.slack.bot-token=" + TOKEN).run(ctx -> assertThat(ctx).hasFailed()
                .getFailure().rootCause().hasMessageContaining("SLACK_CHANNEL_ID").hasMessageNotContaining(TOKEN));
    }

    @Test
    void postsBlockKitWithBearerTokenChannelAndEveryCitedEvidenceId(CapturedOutput output) {
        SLACK.stubFor(post(urlPathEqualTo("/api/chat.postMessage")).willReturn(okJson("{\"ok\":true,\"ts\":\"1.2\"}")));

        slack().run(ctx -> {
            ReportPublisher publisher = ctx.getBean(ReportPublisher.class);
            assertThat(publisher.channelNames()).containsExactly("log", "slack");

            publisher.publish(incident(), report());

            SLACK.verify(postRequestedFor(urlPathEqualTo("/api/chat.postMessage"))
                    .withHeader("Authorization", equalTo("Bearer " + TOKEN))
                    .withRequestBody(matchingJsonPath("$.channel", equalTo(CHANNEL)))
                    .withRequestBody(matchingJsonPath("$.text"))
                    .withRequestBody(matchingJsonPath("$.blocks[0].type", equalTo("header"))));
            String body = SLACK.getAllServeEvents().get(0).getRequest().getBodyAsString();
            assertThat(citedIds(report())).isNotEmpty().allSatisfy(id -> assertThat(body).contains("`" + id + "`"));
            assertThat(body).contains("Checkout errors — COMPLETED", "Timeline (IST)", "`20:08`",
                    "2026-11-23 20:10 IST", "correlated, not established", "rollback, requires approval",
                    "5xx &lt;spike&gt; &amp; latency", "https://investigator.example/incidents/" + INC);
            assertThat(output).contains("report posted to Slack incident_id=" + INC).doesNotContain(TOKEN);
        });
    }

    @Test
    void okFalseIsLoggedAndLeavesTheStoredReportAndLaterChannelsAlone(CapturedOutput output) {
        SLACK.stubFor(post(urlPathEqualTo("/api/chat.postMessage"))
                .willReturn(okJson("{\"ok\":false,\"error\":\"channel_not_found\"}")));
        RecordingChannel later = new RecordingChannel();

        slack().withBean("later", ReportChannel.class, () -> later).run(ctx -> {
            ReportRepository reports = ctx.getBean(ReportRepository.class);
            InvestigationReport report = report();
            reports.saveIfAbsent(report);

            ctx.getBean(ReportPublisher.class).publish(incident(), report);

            assertThat(reports.find(INC)).contains(report);
            assertThat(later.received).containsExactly(report);
            assertThat(output).contains("report channel failed channel=slack incident_id=" + INC)
                    .contains("error=channel_not_found")
                    .contains("investigation report incident_id=" + INC)
                    .doesNotContain(TOKEN);
        });
    }

    @Test
    void slackPropertiesMaskSecrets() {
        slack().withPropertyValues("investigator.slack.signing-secret=signing-test-only-secret").run(ctx ->
                assertThat(ctx.getBean(dev.investigator.config.SlackProperties.class).toString())
                        .doesNotContain(TOKEN).doesNotContain("signing-test-only-secret").contains("****"));
    }

    private static final class RecordingChannel implements ReportChannel {

        final List<InvestigationReport> received = new ArrayList<>();

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public void publish(InvestigationReport report, ReportView view) {
            received.add(report);
        }
    }

    private static Set<String> citedIds(InvestigationReport r) {
        Set<String> ids = new LinkedHashSet<>();
        r.hypotheses().forEach(h -> {
            ids.addAll(h.supportingEvidenceIds());
            ids.addAll(h.contradictingEvidenceIds());
        });
        r.timeline().entries().forEach(e -> ids.addAll(e.evidenceIds()));
        r.actions().forEach(a -> ids.addAll(a.evidenceIds()));
        r.evidence().forEach(e -> ids.add(e.id()));
        return ids;
    }

    private static Incident incident() {
        return new Incident(INC, "Checkout errors", Severity.CRITICAL, "checkout-api", T0, "HighErrorRate",
                Map.of("service", "checkout-api"), List.of(), "f".repeat(64), IncidentStatus.REPORTED);
    }

    private static InvestigationReport report() {
        return new InvestigationReport(INC, InvestigationStatus.COMPLETED, "5xx <spike> & latency",
                new IncidentTimeline(List.of(
                        new TimelineEntry(T0.minusSeconds(120), "errors start", List.of(id(1))),
                        new TimelineEntry(T0.minusSeconds(60), "deploy v2", List.of(id(2))))),
                List.of(new Hypothesis("bad deploy", Confidence.MEDIUM, List.of(id(1), id(2)), List.of(id(3)), false)),
                List.of(new RecommendedAction(1, ActionType.INVESTIGATE, "check logs", false, List.of(id(3))),
                        new RecommendedAction(2, ActionType.ROLLBACK, "roll back v2", true, List.of(id(2), id(4)))),
                List.of("why now?"),
                List.of(ref(1, EvidenceType.METRIC), ref(2, EvidenceType.DEPLOYMENT), ref(3, EvidenceType.LOG),
                        ref(4, EvidenceType.TRACE)),
                T0.plusSeconds(300));
    }

    private static EvidenceRef ref(int n, EvidenceType type) {
        return new EvidenceRef(id(n), type, "src", T0.minusSeconds(60), "headline " + n, "citation " + n);
    }

    private static String id(int n) {
        return "ev-" + INC + "-" + n;
    }
}
