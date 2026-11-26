package dev.investigator.mock;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings bound from {@code mock.*} in application.yml (env: APP_ROLE, DOWNSTREAM_URL, ...). */
@ConfigurationProperties("mock")
public record MockProperties(
        Role role,
        URI downstreamUrl,
        Duration gatewayReadTimeout,
        Duration checkoutReadTimeout) {

    public enum Role { CHECKOUT, GATEWAY, PROVIDER }
}
