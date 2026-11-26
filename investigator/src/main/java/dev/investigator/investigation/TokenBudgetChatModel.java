package dev.investigator.investigation;

import dev.investigator.logging.LogFields;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Enforces the per-investigation Token_Budget around the agent's Chat_Model (Req 6.5).
 *
 * <p>Before each request it finds the running investigation by the {@code incident_id} in the MDC of the
 * agent thread (set by the orchestrator) and throws {@link TokenBudgetExceededException} instead of calling
 * the model once {@link InvestigationContext#tokenBudgetReached()}; after each response it adds the
 * response's input plus output tokens to the context. The check is here rather than in a
 * {@code ChatModelListener} because LangChain4j 1.24.0 logs and ignores listener exceptions, so a listener
 * cannot stop a request. Requests outside an investigation pass through unchanged. The delegate's own
 * listeners (audit, spans, meters) still run.
 */
public final class TokenBudgetChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(TokenBudgetChatModel.class);

    private final ChatModel delegate;
    private final InvestigationContextRegistry contexts;

    public TokenBudgetChatModel(ChatModel delegate, InvestigationContextRegistry contexts) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        InvestigationContext ctx = admit();
        return account(ctx, delegate.chat(request));
    }

    @Override
    public ChatResponse chat(ChatRequest request, ChatRequestOptions options) {
        InvestigationContext ctx = admit();
        return account(ctx, delegate.chat(request, options));
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        return chat(request);
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return delegate.defaultRequestParameters();
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return delegate.supportedCapabilities();
    }

    @Override
    public ModelProvider provider() {
        return delegate.provider();
    }

    /** The current investigation's context, after checking its Token_Budget; null outside one. */
    private InvestigationContext admit() {
        String incidentId = MDC.get(LogFields.INCIDENT_ID);
        Optional<InvestigationContext> found = incidentId == null ? Optional.empty() : contexts.find(incidentId);
        if (found.isEmpty()) {
            return null;
        }
        InvestigationContext ctx = found.get();
        if (ctx.tokenBudgetReached()) {
            log.warn("token budget reached; LLM request not sent incident_id={} llm_tokens={} budget={}",
                    incidentId, ctx.llmTokens(), ctx.maxLlmTokens());
            throw new TokenBudgetExceededException(ctx.maxLlmTokens());
        }
        return ctx;
    }

    private static ChatResponse account(InvestigationContext ctx, ChatResponse response) {
        if (ctx != null && response != null && response.metadata() != null) {
            ctx.addLlmTokens(tokens(response.metadata().tokenUsage()));
        }
        return response;
    }

    /** Input plus output tokens; a missing count counts as zero. */
    static long tokens(TokenUsage usage) {
        if (usage == null) {
            return 0;
        }
        long input = usage.inputTokenCount() == null ? 0 : usage.inputTokenCount();
        long output = usage.outputTokenCount() == null ? 0 : usage.outputTokenCount();
        return input + output;
    }
}
