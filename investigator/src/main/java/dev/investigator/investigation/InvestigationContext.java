package dev.investigator.investigation;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime state of one running investigation (design "Investigation context and window").
 *
 * <p>The immutable part (IDs, window, deadline, Scenario_Anchor, budgets) is fixed at creation. The
 * mutable part is the tool-call counter, the budget-exhausted flag, the LLM token counter, and the
 * {@code accepting} flag, which is true only while the Investigation is {@code RUNNING} and starts false.
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
    private final long maxLlmTokens;
    private final AtomicInteger used = new AtomicInteger();
    private final AtomicBoolean budgetExhausted = new AtomicBoolean();
    private final AtomicBoolean accepting = new AtomicBoolean();
    private final AtomicLong llmTokens = new AtomicLong();

    /** A context without a Token_Budget. */
    public InvestigationContext(
            String incidentId,
            Instant windowStart,
            Instant windowEnd,
            Instant deadline,
            String traceId,
            String alertEvidenceId,
            Instant anchor,
            int budget) {
        this(incidentId, windowStart, windowEnd, deadline, traceId, alertEvidenceId, anchor, budget, Long.MAX_VALUE);
    }

    /**
     * @param anchor the Scenario_Anchor for the mock integrations, normally the Incident's
     *     {@code detectedAt}
     * @param budget the Tool_Call_Budget, at least 1
     * @param maxLlmTokens the Token_Budget: input plus output LLM tokens, at least 1 (Req 6.5)
     */
    public InvestigationContext(
            String incidentId,
            Instant windowStart,
            Instant windowEnd,
            Instant deadline,
            String traceId,
            String alertEvidenceId,
            Instant anchor,
            int budget,
            long maxLlmTokens) {
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
        if (maxLlmTokens < 1) {
            throw new IllegalArgumentException("maxLlmTokens must be at least 1");
        }
        this.maxLlmTokens = maxLlmTokens;
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

    /** The Token_Budget (Req 6.5). */
    public long maxLlmTokens() {
        return maxLlmTokens;
    }

    /** Input plus output LLM tokens reported so far. */
    public long llmTokens() {
        return llmTokens.get();
    }

    /** Adds the tokens of one LLM response; negative counts are ignored. */
    public void addLlmTokens(long tokens) {
        if (tokens > 0) {
            llmTokens.accumulateAndGet(tokens, (a, b) -> a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b);
        }
    }

    /** True once the reported tokens reach the Token_Budget; no further LLM request may be issued. */
    public boolean tokenBudgetReached() {
        return llmTokens.get() >= maxLlmTokens;
    }

    @Override
    public String toString() {
        return "InvestigationContext[incidentId=" + incidentId + ", windowStart=" + windowStart
                + ", windowEnd=" + windowEnd + ", deadline=" + deadline + ", traceId=" + traceId
                + ", anchor=" + anchor + ", budget=" + budget + ", used=" + used.get()
                + ", budgetExhausted=" + budgetExhausted.get() + ", accepting=" + accepting.get()
                + ", maxLlmTokens=" + maxLlmTokens + ", llmTokens=" + llmTokens.get() + "]";
    }
}
