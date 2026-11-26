package dev.investigator.report;

import com.fasterxml.jackson.databind.JsonNode;
import dev.investigator.config.SlackProperties;
import dev.investigator.domain.InvestigationReport;
import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.annotation.Order;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Slack_Channel: posts each stored report to the configured Slack channel with {@code chat.postMessage}
 * as a Block Kit message built by {@link SlackBlockKitRenderer} (Req 13.1, 13.2).
 *
 * <p>Created only when both {@code investigator.slack.bot-token} and {@code investigator.slack.channel-id}
 * are non-blank; {@code application.yml} binds them as {@code ${SLACK_BOT_TOKEN:}}, so an unset variable is
 * an empty but present property, which {@code @ConditionalOnProperty} would treat as set (CP-61, CP-68).
 *
 * <p>Slack reports most errors as HTTP 200 with {@code "ok": false}; this and any HTTP or I/O failure
 * throws a {@link SlackPublishException} whose message holds only the Slack error code, HTTP status or
 * exception type, never the token or the request (Req 13.4, 13.6). {@link ReportPublisher} logs it.
 *
 * <p>When the {@link ApprovalService} exists (same condition), each Approvable_Action first gets a
 * {@code PENDING} approval, and the message carries its Approve/Reject buttons (Req 7.1).
 */
@Component
@Order(100)
@Conditional(SlackReportChannel.OnSlackConfigured.class)
public class SlackReportChannel implements ReportChannel {

    static final String POST_MESSAGE = "/chat.postMessage";

    private static final Logger log = LoggerFactory.getLogger(SlackReportChannel.class);

    private final SlackProperties properties;
    private final SlackBlockKitRenderer renderer;
    private final String publicBaseUrl;
    private final RestClient client;
    private final ApprovalService approvals;

    public SlackReportChannel(SlackProperties properties, SlackBlockKitRenderer renderer,
            @Value("${investigator.public-base-url:http://localhost:8080}") String publicBaseUrl,
            ObjectProvider<ApprovalService> approvals) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.approvals = approvals.getIfAvailable();
        this.publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl.strip();
        if (!properties.configured()) {
            throw new IllegalStateException(SlackProperties.BOT_TOKEN_PROPERTY + " and "
                    + SlackProperties.CHANNEL_ID_PROPERTY + " must be set");
        }
        // HTTP/1.1 avoids the h2c upgrade attempt the JDK client makes on plain http.
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(http);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.client = RestClient.builder()
                .baseUrl(properties.apiUrl().strip())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.botToken().strip())
                .build();
    }

    @Override
    public String name() {
        return "slack";
    }

    @Override
    public void publish(InvestigationReport report, ReportView view) {
        List<Approval> pending = approvals == null ? List.of() : approvals.request(report);
        SlackBlockKitRenderer.SlackMessage message = renderer.render(view, reportUrl(view.incidentId()), pending);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel", properties.channelId().strip());
        body.put("text", message.text());
        body.put("blocks", message.blocks());
        body.put("unfurl_links", false);

        JsonNode response;
        try {
            response = client.post()
                    .uri(POST_MESSAGE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new SlackPublishException("chat.postMessage returned HTTP " + e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            throw new SlackPublishException("chat.postMessage I/O error ("
                    + (e.getCause() == null ? e.getClass() : e.getCause().getClass()).getSimpleName() + ")");
        } catch (RestClientException e) {
            throw new SlackPublishException("chat.postMessage failed (" + e.getClass().getSimpleName() + ")");
        }
        if (response == null || !response.path("ok").asBoolean(false)) {
            String error = response == null ? "empty_response" : errorCode(response.path("error").asText(""));
            throw new SlackPublishException("chat.postMessage returned ok=false error=" + error);
        }
        log.info("report posted to Slack incident_id={} blocks={} approvals={}", report.incidentId(),
                message.blocks().size(), pending.size());
    }

    /** Absolute URL of the report page, or {@code null} when the public base URL is not an http(s) URL. */
    String reportUrl(String incidentId) {
        if (!StringUtils.hasText(incidentId)
                || !(publicBaseUrl.startsWith("http://") || publicBaseUrl.startsWith("https://"))) {
            return null;
        }
        try {
            return UriComponentsBuilder.fromUriString(publicBaseUrl)
                    .pathSegment("incidents", incidentId)
                    .encode()
                    .toUriString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Slack error codes are short snake_case words; anything else is reduced to that shape. */
    private static String errorCode(String raw) {
        String code = raw.replaceAll("[^A-Za-z0-9_.-]", "");
        if (code.isEmpty()) {
            return "unknown";
        }
        return code.length() > 64 ? code.substring(0, 64) : code;
    }

    /** A Slack call failed; the message holds no secret. */
    public static class SlackPublishException extends RuntimeException {

        public SlackPublishException(String message) {
            super(message);
        }
    }

    /** Matches when the bot token and channel ID properties are both non-blank. */
    static class OnSlackConfigured extends SpringBootCondition {

        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            ConditionMessage.Builder message = ConditionMessage.forCondition("Slack report channel");
            String token = context.getEnvironment().getProperty(SlackProperties.BOT_TOKEN_PROPERTY);
            String channel = context.getEnvironment().getProperty(SlackProperties.CHANNEL_ID_PROPERTY);
            if (StringUtils.hasText(token) && StringUtils.hasText(channel)) {
                return ConditionOutcome.match(message.found("non-blank properties")
                        .items(SlackProperties.BOT_TOKEN_PROPERTY, SlackProperties.CHANNEL_ID_PROPERTY));
            }
            return ConditionOutcome.noMatch(message.didNotFind("non-blank properties")
                    .items(SlackProperties.BOT_TOKEN_PROPERTY, SlackProperties.CHANNEL_ID_PROPERTY));
        }
    }
}
