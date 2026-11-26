package dev.investigator.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

/**
 * Slack_Config, bound from {@code investigator.slack.*}, which {@code application.yml} fills from
 * {@code SLACK_BOT_TOKEN}, {@code SLACK_SIGNING_SECRET}, {@code SLACK_CHANNEL_ID} and {@code SLACK_API_URL}
 * (Req 13.1, 15.1, 15.2).
 *
 * <p>Slack publishing is on only when both the bot token and the channel ID are non-blank. Setting one
 * without the other fails startup naming the missing variable (Req 15.4). The signing secret is not used
 * for posting; it is kept for Slack interactions (Phase 3). {@link #toString()} masks both secrets
 * (Req 13.6, 15.5).
 *
 * @param botToken Slack bot token ({@code xoxb-...}), sent as {@code Authorization: Bearer ...}
 * @param signingSecret Slack signing secret for verifying inbound Slack requests
 * @param channelId channel to post reports to, for example {@code C0123456789}
 * @param apiUrl Slack Web API base URL, default {@code https://slack.com/api}
 * @param connectTimeout TCP connect timeout of each Slack call
 * @param readTimeout time to wait for each Slack response
 */
@Validated
@ConfigurationProperties(SlackProperties.PREFIX)
public record SlackProperties(
        String botToken,
        String signingSecret,
        String channelId,
        @NotBlank @DefaultValue("https://slack.com/api") String apiUrl,
        @NotNull @DurationMin(millis = 1) @DefaultValue("2s") Duration connectTimeout,
        @NotNull @DurationMin(millis = 1) @DefaultValue("10s") Duration readTimeout) {

    public static final String PREFIX = "investigator.slack";
    public static final String BOT_TOKEN_PROPERTY = PREFIX + ".bot-token";
    public static final String CHANNEL_ID_PROPERTY = PREFIX + ".channel-id";

    static final String MASK = "****";

    public SlackProperties {
        boolean token = StringUtils.hasText(botToken);
        boolean channel = StringUtils.hasText(channelId);
        if (token && !channel) {
            throw new IllegalArgumentException(
                    CHANNEL_ID_PROPERTY + " (SLACK_CHANNEL_ID) must be set when SLACK_BOT_TOKEN is set");
        }
        if (channel && !token) {
            throw new IllegalArgumentException(
                    BOT_TOKEN_PROPERTY + " (SLACK_BOT_TOKEN) must be set when SLACK_CHANNEL_ID is set");
        }
        if (StringUtils.hasText(apiUrl) && !isHttpUrl(apiUrl.strip())) {
            throw new IllegalArgumentException(PREFIX + ".api-url must be an absolute http or https URL");
        }
    }

    /** Whether the bot token and channel ID are both non-blank. */
    public boolean configured() {
        return StringUtils.hasText(botToken) && StringUtils.hasText(channelId);
    }

    /** All settings with the bot token and signing secret replaced by {@code ****} when set. */
    @Override
    public String toString() {
        return "SlackProperties[botToken=" + mask(botToken)
                + ", signingSecret=" + mask(signingSecret)
                + ", channelId=" + (channelId == null ? "" : channelId)
                + ", apiUrl=" + apiUrl
                + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout
                + "]";
    }

    private static String mask(String secret) {
        return secret == null || secret.isEmpty() ? "" : MASK;
    }

    private static boolean isHttpUrl(String value) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return (scheme.equals("http") || scheme.equals("https")) && StringUtils.hasText(uri.getHost());
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
