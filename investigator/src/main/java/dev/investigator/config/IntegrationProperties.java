package dev.investigator.config;

import jakarta.validation.Valid;
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
 * Endpoints and credentials of the real integrations, bound from
 * {@code investigator.integrations.{metrics,logs,traces,changes}.*}; {@code application-real.yml} fills
 * each from its own environment variable (Req 7.5, 7.6, 15.2).
 *
 * <p>An integration whose {@code base-url} is unset or blank is not configured, and its mock client is
 * used (Req 7.2). A non-blank {@code base-url} that is not an absolute http(s) URL, or an incomplete
 * credential, fails startup with a message naming the property (Req 7.7, 15.4). {@link #toString()}
 * masks every credential (Req 15.5).
 */
@Validated
@ConfigurationProperties(IntegrationProperties.PREFIX)
public record IntegrationProperties(
        @NotNull @Valid @DefaultValue Endpoint metrics,
        @NotNull @Valid @DefaultValue Endpoint logs,
        @NotNull @Valid @DefaultValue Endpoint traces,
        @NotNull @Valid @DefaultValue Endpoint changes) {

    public static final String PREFIX = "investigator.integrations";

    public IntegrationProperties {
        check("metrics", metrics);
        check("logs", logs);
        check("traces", traces);
        check("changes", changes);
    }

    /** The {@code base-url} property name of {@code integration}, for conditions and error messages. */
    public static String baseUrlProperty(String integration) {
        return PREFIX + "." + integration + ".base-url";
    }

    private static void check(String integration, Endpoint endpoint) {
        if (endpoint == null) {
            return;
        }
        String prefix = PREFIX + "." + integration + ".";
        if (endpoint.configured() && !isHttpUrl(endpoint.baseUrl().strip())) {
            // The value is not echoed: a URL may carry user info.
            throw new IllegalArgumentException(prefix + "base-url must be an absolute http or https URL");
        }
        boolean user = StringUtils.hasText(endpoint.username());
        boolean password = StringUtils.hasText(endpoint.password());
        if (user != password) {
            throw new IllegalArgumentException(
                    prefix + "username and " + prefix + "password must be set together");
        }
        if (user && StringUtils.hasText(endpoint.bearerToken())) {
            throw new IllegalArgumentException(
                    "set either " + prefix + "bearer-token or " + prefix + "username/password, not both");
        }
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

    /**
     * One integration endpoint.
     *
     * @param baseUrl backend base URL, for example {@code http://prometheus:9090}; blank means not configured
     * @param bearerToken bearer credential sent as {@code Authorization: Bearer ...}; never logged
     * @param username basic-auth user name, set together with {@code password}
     * @param password basic-auth password; never logged
     * @param connectTimeout TCP connect timeout of every request (Req 3.6)
     * @param readTimeout time to wait for the response of every request (Req 3.6)
     */
    public record Endpoint(
            String baseUrl,
            String bearerToken,
            String username,
            String password,
            @NotNull @DurationMin(millis = 1) @DefaultValue("2s") Duration connectTimeout,
            @NotNull @DurationMin(millis = 1) @DefaultValue("10s") Duration readTimeout) {

        static final String MASK = "****";

        /** Whether a non-blank base URL is set. */
        public boolean configured() {
            return StringUtils.hasText(baseUrl);
        }

        /** All settings with the bearer token and password replaced by {@code ****} when set. */
        @Override
        public String toString() {
            return "Endpoint[baseUrl=" + (baseUrl == null ? "" : baseUrl)
                    + ", bearerToken=" + mask(bearerToken)
                    + ", username=" + (username == null ? "" : username)
                    + ", password=" + mask(password)
                    + ", connectTimeout=" + connectTimeout
                    + ", readTimeout=" + readTimeout
                    + "]";
        }

        private static String mask(String secret) {
            return secret == null || secret.isEmpty() ? "" : MASK;
        }
    }
}
