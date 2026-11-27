package dev.investigator.investigation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Blank assistant messages are replaced before the request reaches the model; others pass unchanged. */
class BlankAiMessageChatModelTest {

    private final List<ChatRequest> seen = new ArrayList<>();
    private final ChatModel delegate = new ChatModel() {
        @Override
        public ChatResponse doChat(ChatRequest request) {
            seen.add(request);
            return ChatResponse.builder().aiMessage(AiMessage.from("{}")).build();
        }
    };

    @Test
    void blankAiMessageIsReplacedWithThePlaceholder() {
        AiMessage toolCall = AiMessage.from(ToolExecutionRequest.builder()
                .id("t1").name("searchLogs").arguments("{}").build());
        ChatRequest request = ChatRequest.builder().messages(SystemMessage.from("sys"), UserMessage.from("go"),
                toolCall, AiMessage.from(""), UserMessage.from("correct it")).build();

        new BlankAiMessageChatModel(delegate).chat(request);

        List<ChatMessage> sent = seen.getFirst().messages();
        assertThat(sent).hasSize(5);
        assertThat(sent.get(2)).isSameAs(toolCall);
        assertThat(sent.get(3)).isEqualTo(AiMessage.from(BlankAiMessageChatModel.PLACEHOLDER));
        assertThat(sent.get(4)).isEqualTo(UserMessage.from("correct it"));
    }

    @Test
    void requestWithoutBlankMessagesIsPassedAsIs() {
        ChatRequest request = ChatRequest.builder()
                .messages(UserMessage.from("go"), AiMessage.from("answer"), UserMessage.from("again")).build();

        new BlankAiMessageChatModel(delegate).chat(request);

        assertThat(BlankAiMessageChatModel.withoutBlankAiMessages(request)).isSameAs(request);
        assertThat(seen.getFirst().messages()).isEqualTo(request.messages());
    }
}
