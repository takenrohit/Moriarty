package dev.investigator.investigation;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Replaces blank assistant messages in the request history with a placeholder before calling the
 * Chat_Model.
 *
 * <p>A model can answer with no text and no tool call (Bedrock Nova did after a run of failed tool calls).
 * The empty answer is stored in chat memory and sent back with the correction request, and the Bedrock
 * Converse API rejects any message with empty content (HTTP 400), so the correction round failed and the
 * investigation ended {@code FAILED}. With the placeholder, the correction request is accepted and the
 * model sees that its previous answer was empty. Chat memory itself is not changed.
 */
public final class BlankAiMessageChatModel implements ChatModel {

    /** Text sent in place of a blank assistant message. */
    static final String PLACEHOLDER = "(empty response)";

    private final ChatModel delegate;

    public BlankAiMessageChatModel(ChatModel delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        return delegate.chat(withoutBlankAiMessages(request));
    }

    @Override
    public ChatResponse chat(ChatRequest request, ChatRequestOptions options) {
        return delegate.chat(withoutBlankAiMessages(request), options);
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

    /** {@code request} with each blank assistant message (no text, no tool call) replaced; else unchanged. */
    static ChatRequest withoutBlankAiMessages(ChatRequest request) {
        List<ChatMessage> messages = request.messages();
        if (messages.stream().noneMatch(BlankAiMessageChatModel::isBlank)) {
            return request;
        }
        List<ChatMessage> replaced = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            replaced.add(isBlank(message) ? AiMessage.from(PLACEHOLDER) : message);
        }
        return request.toBuilder().messages(replaced).build();
    }

    private static boolean isBlank(ChatMessage message) {
        return message instanceof AiMessage ai && !ai.hasToolExecutionRequests()
                && (ai.text() == null || ai.text().isBlank());
    }
}
