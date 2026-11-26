package dev.investigator.integration.mock;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The failure scenarios of the Scenario_Library (Req 10.1 to 10.3, CP-75), selected by an alert's
 * {@value #LABEL} label. Each kind declares the cause a correct report must name.
 */
public enum ScenarioKind {

    /** The demo of {@code demo-scenario.md}: payment-gateway timeouts; the recent deploy is only correlated. */
    PAYMENT_GATEWAY(new ExpectedCause(
            List.of("payment-gateway", "payment gateway", "payment-provider", "payment provider"),
            List.of("timeout", "timed out", "time out", "times out"), false)),
    /** checkout-api v2.14.3 breaks the payment client; the recent deploy is the cause. */
    BAD_DEPLOY(new ExpectedCause(List.of("checkout-api", "v2.14.3"), List.of("deploy", "v2.14.3", "release"),
            true)),
    /** checkout-api exhausts its database connection pool; no recent deploy. */
    DB_POOL_EXHAUSTION(new ExpectedCause(List.of("checkout-api", "postgres", "database"),
            List.of("connection pool", "hikari", "pool exhaust"), false)),
    /** checkout-api is CPU-throttled by a noisy neighbour; no failing dependency, no recent deploy. */
    NOISY_NEIGHBOUR(new ExpectedCause(List.of("checkout-api"),
            List.of("cpu", "throttl", "noisy neighbour", "noisy neighbor"), false));

    /** The Incident label that selects the scenario. */
    public static final String LABEL = "scenario";

    /** The scenario served when the label is absent or unknown (Req 10.2). */
    public static final ScenarioKind DEFAULT = PAYMENT_GATEWAY;

    private final ExpectedCause expectedCause;

    ScenarioKind(ExpectedCause expectedCause) {
        this.expectedCause = expectedCause;
    }

    public ExpectedCause expectedCause() {
        return expectedCause;
    }

    /**
     * The kind named by a {@value #LABEL} label value: case-insensitive, with {@code -}, {@code _} and
     * spaces equivalent, {@code noisy-neighbor} accepted for {@link #NOISY_NEIGHBOUR}. Null, blank or
     * unknown values give {@link #DEFAULT}. Pure (Req 10.5).
     */
    public static ScenarioKind fromLabel(String value) {
        if (value == null) {
            return DEFAULT;
        }
        String key = value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (key.equals("NOISY_NEIGHBOR")) {
            return NOISY_NEIGHBOUR;
        }
        for (ScenarioKind kind : values()) {
            if (kind.name().equals(key)) {
                return kind;
            }
        }
        return DEFAULT;
    }

    /**
     * What a correct report says about a scenario (Req 10.4).
     *
     * @param causeServices the Primary_Hypothesis names at least one of these (lower case)
     * @param causeKeywords the Primary_Hypothesis contains at least one of these (lower case)
     * @param deployIsCause whether the recent deployment is the established cause
     */
    public record ExpectedCause(List<String> causeServices, List<String> causeKeywords, boolean deployIsCause) {

        public ExpectedCause {
            causeServices = List.copyOf(Objects.requireNonNull(causeServices, "causeServices"));
            causeKeywords = List.copyOf(Objects.requireNonNull(causeKeywords, "causeKeywords"));
        }
    }
}
