package dev.investigator.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.investigator.config.JacksonConfig;
import dev.investigator.integration.logs.LogSearchResult;
import dev.investigator.integration.metrics.MetricSeries;
import dev.investigator.integration.mock.MockScenario;
import dev.investigator.integration.traces.TraceSearchResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Summaries of the demo scenario and {@link ResultCapper} examples. */
class SummarizersTest {

    private static final Instant A = Instant.parse("2026-11-23T14:40:00Z");
    private static final Instant WS = A.minus(Duration.ofMinutes(60));
    private static final Instant WE = A.plus(Duration.ofMinutes(10));

    private final MockScenario scenario = MockScenario.forAnchor(A);
    private final ObjectMapper mapper = JacksonConfig.jsonCodec();

    @Test
    void emptyMetricSeriesHasNullStatistics() {
        MetricSummary summary = new MetricSummarizer().summarize("ev-1", "checkout-api", "up", WS, WE, false,
                new MetricSeries("up", Duration.ofSeconds(15), List.of()));

        assertThat(summary.pointCount()).isZero();
        assertThat(summary.min()).isNull();
        assertThat(summary.baseline()).isNull();
        assertThat(summary.changePointAt()).isNull();
        assertThat(summary.headline()).isEqualTo("checkout-api metric: no data points");
    }

    @Test
    void demoLogsSummarizeTo214Timeouts() {
        LogSearchResult result = scenario.logs("checkout-api", "|= \"timeout\"", WS, WE, 500);
        LogSummary summary = new LogSummarizer(5).summarize("ev-3", "checkout-api", "|= \"timeout\"", WS, WE, false, result);

        assertThat(summary.matchCount()).isEqualTo(214);
        assertThat(summary.truncated()).isFalse();
        assertThat(summary.topMessages()).containsExactly(new LogSummary.MessageCount(MockScenario.TIMEOUT_MESSAGE, 214));
        assertThat(summary.firstSeen()).isEqualTo(Instant.parse("2026-11-23T14:38:00Z"));
        assertThat(summary.lastSeen()).isEqualTo(result.entries().get(213).at());
        assertThat(summary.headline()).startsWith("checkout-api logs: 214 matches, top \"PaymentGatewayTimeoutException");
    }

    @Test
    void demoTracesPointAtPaymentProvider() {
        TraceSearchResult result = scenario.traces("checkout-api", WS, WE, Duration.ofSeconds(2), 50);
        TraceSummary summary = new TraceSummarizer(3, Duration.ofSeconds(2))
                .summarize("ev-4", "checkout-api", WS, WE, false, result);

        assertThat(summary.errorTraceCount()).isEqualTo(50);
        assertThat(summary.slowTraceCount()).isEqualTo(50);
        assertThat(summary.truncated()).isTrue();
        // All three spans of the failing chain fail equally often; the deepest one comes first.
        assertThat(summary.topFailingSpans()).first()
                .isEqualTo(new TraceSummary.FailingSpan("payment-gateway", "POST /authorize", "payment-provider", 50));
        assertThat(summary.headline())
                .endsWith("top failing span payment-gateway POST /authorize -> payment-provider (50)");
        assertThat(summary.exampleTraceIds()).hasSize(3).containsExactly(
                result.traces().get(0).traceId(), result.traces().get(1).traceId(), result.traces().get(2).traceId());
    }

    @Test
    void changeSummaryKeepsTwentyFilesAndHasDeployHeadline() {
        var change = ChangesSummary.Change.from("ev-5",
                scenario.changes("checkout-api", WE.minus(Duration.ofHours(24)), WE).get(0), 20);

        assertThat(change.headline()).isEqualTo("checkout-api v2.14.3 deployed by dev-alice");
        assertThat(change.changedFiles()).hasSizeLessThanOrEqualTo(20);
    }

    @Test
    void smallSummaryIsUnchanged() throws Exception {
        String json = ResultCapper.cap(mapper, Map.of("evidenceId", "ev-1", "n", 1), "ev-1", 4096);

        assertThat(mapper.readTree(json)).isEqualTo(mapper.readTree("{\"evidenceId\":\"ev-1\",\"n\":1}"));
    }

    @Test
    void longStringsAreCutByCodePointAndArraysTrimmed() throws Exception {
        String emoji = "\uD83D\uDE00";
        Map<String, Object> summary = Map.of(
                "evidenceId", "ev-7",
                "text", emoji.repeat(500),
                "items", Collections.nCopies(400, "item"));
        // Jackson writes each surrogate as a 6-byte JSON escape, so 199 emoji take about 2.4 KB.
        String json = ResultCapper.cap(mapper, summary, "ev-7", 4096);

        assertThat(json.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(4096);
        JsonNode node = mapper.readTree(json);
        assertThat(node.get("evidenceId").asText()).isEqualTo("ev-7");
        String text = node.get("text").asText();
        assertThat(text.codePointCount(0, text.length())).isEqualTo(200);
        assertThat(text).isEqualTo(emoji.repeat(199) + "…");
        assertThat(node.get("items").size()).isBetween(1, 399);
    }

    @Test
    void oversizedSummaryFallsBack() throws Exception {
        Map<String, Object> summary = Map.of("evidenceId", "ev-8", "x", "y".repeat(150), "z", "w".repeat(150));
        String json = ResultCapper.cap(mapper, summary, "ev-8", 256);

        assertThat(mapper.readTree(json)).isEqualTo(mapper.readTree(
                "{\"evidenceId\":\"ev-8\",\"truncated\":true,\"note\":\"summary exceeded size cap\"}"));
    }
}
