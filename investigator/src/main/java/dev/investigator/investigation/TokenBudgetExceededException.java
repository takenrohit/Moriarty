package dev.investigator.investigation;

/**
 * Thrown by {@link TokenBudgetChatModel} instead of issuing an LLM request once the investigation's
 * Token_Budget is reached (Req 6.5). The orchestrator ends the investigation {@code INCONCLUSIVE}.
 */
public final class TokenBudgetExceededException extends RuntimeException {

    public TokenBudgetExceededException(long budget) {
        super("token budget of " + budget + " LLM tokens reached", null, false, false);
    }
}
