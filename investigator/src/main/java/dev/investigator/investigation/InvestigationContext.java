package dev.investigator.investigation;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runtime state of one running investigation (design "Investigation context and window").
 *
 * <p>The immutable part (IDs, window, deadline, Scenario_Anchor, budget) is fixed at creation. The
 * mutable part is the tool-call counter, the budget-exhausted flag, and the {@code accepting} flag,
 * which is true only while the Investigation is {@code RUNNING} and starts false.
 */
public final class InvestigationContext {

    private final String incidentId;
    private final Instant windowStart;
    private final Instant windowEnd;
    private final Instant deadline;
    private final String traceId;
    private final String alertEvidenceId;
    private final Instant anchor;
    private final int budget;
    private final AtomicInteger used = new AtomicInteger();
    private final AtomicBoolean budgetExhausted = new AtomicBoolean();
    private final AtomicBoolean accepting = new AtomicBoolean();

    /**
     * @param anchor the Scenario_Anchor for the mock integrations, normally the Incident's
     *     {@code detectedAt}
     * @param budget the Tool_Call_Budget, at least 1
     */
    public InvestigationContext(
            String incidentId,
            Instant windowStart,
            Instant windowEnd,
            Instant deadline,
            String traceId,
            String alertEvidenceId,
            Instant anchor,
            int budget) {
        this.incidentId = Objects.requireNonNull(incidentId, "incidentId");
        this.windowStart = Objects.requireNonNull(windowStart, "windowStart");
        this.windowEnd = Objects.requireNonNull(windowEnd, "windowEnd");
        this.deadline = Objects.requireNonNull(deadline, "deadline");
        this.traceId = Objects.requireNonNull(traceId, "traceId");
        this.alertEvidenceId = Objects.requireNonNull(alertEvidenceId, "alertEvidenceId");
        this.anchor = Objects.requireNonNull(anchor, "anchor");
        if (windowStart.isAfter(windowEnd)) {
            throw new IllegalArgumentException("windowStart must not be after windowEnd");
        }
        if (budget < 1) {
            throw new IllegalArgumentException("budget must be at least 1");
        }
        this.budget = budget;
    }

    public String incidentId() {
        return incidentId;
    }

    public Instant windowStart() {
        return windowStart;
    }

    public Instant windowEnd() {
        return windowEnd;
    }

    public Instant deadline() {
        return deadline;
    }

    public String traceId() {
        return traceId;
    }

    public String alertEvidenceId() {
        return alertEvidenceId;
    }

    public Instant anchor() {
        return anchor;
    }

    public int budget() {
        return budget;
    }

    /** Tool invocations counted so far, including rejected ones (Req 11.10). */
    public AtomicInteger used() {
        return used;
    }

    /** Set when a tool call was refused because the budget was used up (Req 11.11). */
    public AtomicBoolean budgetExhausted() {
        return budgetExhausted;
    }

    /** True only while the Investigation is {@code RUNNING} (Req 11.13). */
    public AtomicBoolean accepting() {
        return accepting;
    }

    @Override
    public String toString() {
        return "InvestigationContext[incidentId=" + incidentId + ", windowStart=" + windowStart
                + ", windowEnd=" + windowEnd + ", deadline=" + deadline + ", traceId=" + traceId
                + ", anchor=" + anchor + ", budget=" + budget + ", used=" + used.get()
                + ", budgetExhausted=" + budgetExhausted.get() + ", accepting=" + accepting.get() + "]";
    }
}
