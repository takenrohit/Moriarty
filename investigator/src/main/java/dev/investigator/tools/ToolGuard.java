package dev.investigator.tools;

import dev.investigator.config.InvestigatorProperties;
import dev.investigator.investigation.InvestigationContext;
import dev.investigator.investigation.InvestigationContextRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Tool_Guard: validates and limits every tool invocation before any Integration_Client is called
 * (Req 11, design "Tool guard").
 *
 * <p>Each tool calls {@link #begin} first, which counts the invocation against the Tool_Call_Budget
 * and refuses it when the Investigation is not {@code RUNNING} or the budget is used up; the tool then
 * validates its arguments with the other methods. Every refusal is a {@link ToolRejectedException},
 * which the tool turns into an error Tool_Result. Rejection texts never echo the rejected value.
 */
@Component
public class ToolGuard {

    static final String NOT_RUNNING = "investigation is not running";
    static final String RETURN_REPORT_HINT = "Stop calling tools and return the report JSON now.";
    static final String BUDGET_EXHAUSTED = "tool-call budget is used up";
    static final String SERVICE_NOT_ALLOWED = "service is not in the allow-list";
    static final String START_NOT_BEFORE_END = "start must be before end";
    static final String OUTSIDE_WINDOW = "requested range is outside the investigation window";
    static final String MALFORMED_ARGUMENTS = "tool arguments could not be parsed";
    static final String MALFORMED_ARGUMENTS_HINT =
            "send a JSON object with exactly the parameters the tool declares, using the declared types";
    static final String TIME_HINT = "use an ISO-8601 instant such as 2026-11-23T14:00:00Z";

    private static final Logger log = LoggerFactory.getLogger(ToolGuard.class);

    private final InvestigationContextRegistry contexts;
    private final InvestigatorProperties.Tools limits;
    private final Set<String> allowList;
    private final String allowListText;

    public ToolGuard(InvestigationContextRegistry contexts, InvestigatorProperties properties) {
        this.contexts = contexts;
        this.limits = properties.tools();
        this.allowList = Set.copyOf(properties.services().keySet());
        List<String> sorted = allowList.stream().sorted().toList();
        this.allowListText = sorted.isEmpty() ? "none" : String.join(", ", sorted);
    }

    /**
     * Counts one invocation of {@code toolName} and returns the context of the running Investigation
     * (Req 11.10, 11.11, 11.13).
     *
     * @throws ToolRejectedException if no Investigation is running for {@code incidentId}, or if this
     *     invocation is over the budget; in the latter case the context is marked budget-exhausted
     */
    public InvestigationContext begin(String incidentId, String toolName) {
        Optional<InvestigationContext> found = incidentId == null ? Optional.empty() : contexts.find(incidentId);
        if (found.isEmpty() || !found.get().accepting().get()) {
            throw new ToolRejectedException(NOT_RUNNING, RETURN_REPORT_HINT);
        }
        InvestigationContext ctx = found.get();
        int n = ctx.used().incrementAndGet();
        if (n > ctx.budget()) {
            if (ctx.budgetExhausted().compareAndSet(false, true)) {
                log.warn("tool-call budget exhausted incident_id={} tool_name={} budget={}",
                        incidentId, toolName, ctx.budget());
            }
            throw new ToolRejectedException(BUDGET_EXHAUSTED + " (" + ctx.budget() + " calls)", RETURN_REPORT_HINT);
        }
        return ctx;
    }

    /**
     * Counts a call whose arguments LangChain4j could not parse, so it never reached the tool, and
     * returns the error Tool_Result for it: the budget or "not running" refusal if {@link #begin}
     * refuses, otherwise {@link #MALFORMED_ARGUMENTS}.
     */
    public String malformedArguments(Object memoryId, String toolName) {
        try {
            begin(memoryId == null ? null : memoryId.toString(), toolName);
        } catch (ToolRejectedException e) {
            return e.toResult();
        }
        log.info("tool arguments rejected incident_id={} tool_name={}", memoryId, toolName);
        return ToolResults.error(MALFORMED_ARGUMENTS, MALFORMED_ARGUMENTS_HINT);
    }

    /** Returns the trimmed service if it is in the Allow_List (Req 11.1, 11.2). */
    public String requireAllowedService(String service) {
        String trimmed = service == null ? "" : service.trim();
        if (!allowList.contains(trimmed)) {
            throw new ToolRejectedException(trimmed.isEmpty() ? "service is required" : SERVICE_NOT_ALLOWED,
                    "use one of the allowed services: " + allowListText);
        }
        return trimmed;
    }

    /**
     * Parses {@code start} and {@code end} and clamps them to the Investigation_Window (Req 11.3 to
     * 11.5). Accepts an ISO-8601 instant ({@code ...Z}) or offset date-time ({@code ...+05:30}). If the
     * clamped range is longer than {@code investigator.tools.max-span} (possible only when the window
     * is configured longer), its start moves forward so the range ends at the same time.
     *
     * @throws ToolRejectedException if a time is missing or unparseable, if {@code start ≥ end}, or if
     *     the range does not overlap the window
     */
    public TimeRange clampToWindow(InvestigationContext ctx, String start, String end) {
        Instant s = parseInstant(ctx, "start", start);
        Instant e = parseInstant(ctx, "end", end);
        if (!s.isBefore(e)) {
            throw new ToolRejectedException(START_NOT_BEFORE_END, windowHint(ctx));
        }
        Instant clampedStart = s.isBefore(ctx.windowStart()) ? ctx.windowStart() : s;
        Instant clampedEnd = e.isAfter(ctx.windowEnd()) ? ctx.windowEnd() : e;
        if (!clampedStart.isBefore(clampedEnd)) {
            throw new ToolRejectedException(OUTSIDE_WINDOW, windowHint(ctx));
        }
        boolean clamped = !clampedStart.equals(s) || !clampedEnd.equals(e);
        Duration maxSpan = limits.maxSpan();
        if (Duration.between(clampedStart, clampedEnd).compareTo(maxSpan) > 0) {
            clampedStart = clampedEnd.minus(maxSpan);
            clamped = true;
        }
        return new TimeRange(clampedStart, clampedEnd, clamped);
    }

    /** Returns the query if it is non-blank and at most {@code max-query-length} characters (Req 11.6). */
    public String requireQuery(String query) {
        return requireText("query", query, limits.maxQueryLength());
    }

    /** Returns the symptom if it is non-blank and at most {@code max-symptom-length} characters (Req 11.7). */
    public String requireSymptom(String symptom) {
        return requireText("symptom", symptom, limits.maxSymptomLength());
    }

    /**
     * The look-back of {@code getRecentChanges}: {@code [windowEnd − h, windowEnd]} with {@code h} capped
     * at {@code max-lookback-hours} (Req 11.8, 11.9). Anchored at the window end and exempt from the
     * window clamp (architecture §6).
     *
     * @throws ToolRejectedException if {@code hours < 1}
     */
    public Lookback lookback(InvestigationContext ctx, int hours) {
        int max = limits.maxLookbackHours();
        if (hours < 1) {
            throw new ToolRejectedException("lookbackHours must be at least 1", "use a value from 1 to " + max);
        }
        boolean clamped = hours > max;
        int applied = clamped ? max : hours;
        Instant until = ctx.windowEnd();
        return new Lookback(applied, until.minus(Duration.ofHours(applied)), until, clamped);
    }

    private static String requireText(String name, String text, int maxLength) {
        if (text == null || text.isBlank()) {
            throw new ToolRejectedException(name + " is required",
                    "pass a non-blank " + name + " of at most " + maxLength + " characters");
        }
        if (text.codePointCount(0, text.length()) > maxLength) {
            throw new ToolRejectedException(name + " is longer than " + maxLength + " characters",
                    "shorten the " + name + " to at most " + maxLength + " characters");
        }
        return text;
    }

    private static Instant parseInstant(InvestigationContext ctx, String name, String text) {
        if (text == null || text.isBlank()) {
            throw new ToolRejectedException(name + " is required", TIME_HINT + "; " + windowHint(ctx));
        }
        String trimmed = text.trim();
        try {
            return Instant.parse(trimmed);
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(trimmed).toInstant();
            } catch (DateTimeParseException e) {
                throw new ToolRejectedException(name + " is not an ISO-8601 instant",
                        TIME_HINT + "; " + windowHint(ctx));
            }
        }
    }

    private static String windowHint(InvestigationContext ctx) {
        return "use a range inside the investigation window [" + ctx.windowStart() + ", " + ctx.windowEnd() + "]";
    }
}
