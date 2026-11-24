package dev.investigator.investigation;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Thread-local binding of the current {@link InvestigationContext}.
 *
 * <p>Integration_Client interfaces carry no Incident_ID, so the mock clients read the Scenario_Anchor
 * from here. {@code ObservabilityTools} wraps each client call in {@link #with}. Tools run on the
 * agent's thread, so a thread-local is safe. Scopes nest: the previous binding is restored when the
 * body returns or throws.
 */
public final class InvestigationScope {

    private static final ThreadLocal<InvestigationContext> CURRENT = new ThreadLocal<>();

    private InvestigationScope() {
    }

    /** Runs {@code body} with {@code ctx} as the current context and returns its result. */
    public static <T> T with(InvestigationContext ctx, Supplier<T> body) {
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(body, "body");
        InvestigationContext previous = CURRENT.get();
        CURRENT.set(ctx);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** The context bound to this thread, if any. */
    public static Optional<InvestigationContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }
}
