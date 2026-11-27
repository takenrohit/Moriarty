package dev.investigator.web;

import dev.investigator.integration.mock.ScenarioKind;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * One Scenario_Preset of the New_Alert_Form (web UI Req 3.1, 3.2): the prefilled alert name, service,
 * severity and extra labels for one mock failure scenario. The {@code key} is the {@value ScenarioKind#LABEL}
 * label value, so {@link ScenarioKind#fromLabel} of it returns {@link #kind()}.
 *
 * @param key scenario label value and {@code ?scenario=} parameter, for example {@code bad-deploy}
 * @param label human-readable name shown on the form
 * @param alert prefilled alert name
 * @param service prefilled affected service
 * @param severity prefilled severity, lower case
 * @param extraLabels labels sent with the alert besides {@code scenario}
 * @param kind the mock scenario the key selects
 */
public record ScenarioPreset(String key, String label, String alert, String service, String severity,
        Map<String, String> extraLabels, ScenarioKind kind) {

    /** ISO-8601 with offset, seconds precision, as {@code AlertNormalizer} parses it. */
    static final DateTimeFormatter DETECTED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT);

    /** The four presets, in display order; the first is the default. */
    public static final List<ScenarioPreset> ALL = List.of(
            new ScenarioPreset("payment-gateway", "Payment gateway degradation", "HighErrorRate", "checkout-api",
                    "critical", Map.of("environment", "production"), ScenarioKind.PAYMENT_GATEWAY),
            new ScenarioPreset("bad-deploy", "Bad deploy (v2.14.3)", "HighErrorRate", "checkout-api",
                    "critical", Map.of("environment", "production"), ScenarioKind.BAD_DEPLOY),
            new ScenarioPreset("db-pool-exhaustion", "DB connection pool exhaustion", "HighErrorRate",
                    "checkout-api", "high", Map.of("environment", "production"), ScenarioKind.DB_POOL_EXHAUSTION),
            new ScenarioPreset("noisy-neighbour", "Noisy neighbour CPU throttle", "HighErrorRate", "checkout-api",
                    "warning", Map.of("environment", "production"), ScenarioKind.NOISY_NEIGHBOUR));

    public ScenarioPreset {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(kind, "kind");
        extraLabels = Map.copyOf(extraLabels);
    }

    /**
     * The preset for a {@code ?scenario=} value, matched like the {@value ScenarioKind#LABEL} label
     * (case-insensitive, {@code -}, {@code _} and space equivalent). Null, blank or unknown values give
     * the {@code payment-gateway} default.
     */
    public static ScenarioPreset byKey(String key) {
        ScenarioKind kind = ScenarioKind.fromLabel(key);
        return ALL.stream().filter(p -> p.kind() == kind).findFirst().orElse(ALL.get(0));
    }

    /** A new form prefilled with this preset and {@code now} as the detected time (Req 3.2, 3.3). */
    public AlertForm toForm(OffsetDateTime now) {
        AlertForm form = new AlertForm();
        form.setAlert(alert);
        form.setService(service);
        form.setSeverity(severity);
        form.setScenario(key);
        form.setDetectedAt(formatDetectedAt(now));
        return form;
    }

    /** {@code now} as ISO-8601 with its offset, truncated to seconds, for example {@code 2026-11-23T20:10:00+05:30}. */
    static String formatDetectedAt(OffsetDateTime now) {
        return DETECTED_AT.format(now.truncatedTo(ChronoUnit.SECONDS));
    }
}
