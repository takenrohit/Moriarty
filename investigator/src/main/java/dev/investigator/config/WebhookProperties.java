package dev.investigator.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Alert webhook settings, bound from {@code investigator.webhook.*}, which {@code application.yml}
 * fills from {@code WEBHOOK_SECRET} and {@code WEBHOOK_SERVICE_LABEL} (Req 8.5, 10.1, 15.1).
 *
 * <p>{@link #toString()} masks the secret (Req 10.7).
 *
 * @param secret shared secret alert callers must present; blank or unset disables webhook
 *     authentication (Req 10.5)
 * @param serviceLabel alert label holding the affected service, default {@code service}
 */
@Validated
@ConfigurationProperties("investigator.webhook")
public record WebhookProperties(String secret, @NotBlank @DefaultValue("service") String serviceLabel) {

    static final String MASK = "****";

    /** Whether a non-blank secret is configured. */
    public boolean authenticationEnabled() {
        return secret != null && !secret.isBlank();
    }

    /** Returns the settings with the secret replaced by {@code ****}, or empty when none is set. */
    @Override
    public String toString() {
        return "WebhookProperties[secret=" + (authenticationEnabled() ? MASK : "")
                + ", serviceLabel=" + serviceLabel + "]";
    }
}
