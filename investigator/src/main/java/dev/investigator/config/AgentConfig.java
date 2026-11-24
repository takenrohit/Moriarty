package dev.investigator.config;

import dev.investigator.investigation.InvestigatorAgent;
import dev.investigator.tools.ObservabilityTools;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the Investigator_Agent AI service (Req 13.1, 13.6).
 *
 * <p>The Chat_Model comes from {@link LlmConfig} in production and from a test configuration when
 * {@code investigator.llm.enabled=false}. It is looked up through an {@link ObjectProvider} when this
 * bean is created, after every bean definition is registered, so the result does not depend on the
 * order in which configuration classes are processed. If no Chat_Model bean exists, the agent is
 * still built, with a model that fails every call; the application starts, and each investigation
 * ends {@code FAILED} with an evidence-only report. A missing {@code LLM_API_KEY} with the LLM enabled
 * still fails startup in {@link LlmConfig} (Req 2.6).
 */
@Configuration(proxyBeanMethods = false)
public class AgentConfig {

    static final String NO_CHAT_MODEL =
            "No ChatModel bean is available. Set investigator.llm.enabled=true and LLM_API_KEY,"
                    + " or register a ChatModel bean.";

    private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);

    @Bean
    InvestigatorAgent investigatorAgent(
            ObjectProvider<ChatModel> chatModels, ObservabilityTools tools, InvestigatorProperties properties) {
        ChatModel chatModel = chatModels.getIfAvailable();
        if (chatModel == null) {
            log.warn("{} Investigations will fail until a ChatModel is configured.", NO_CHAT_MODEL);
            chatModel = new UnavailableChatModel();
        }
        int maxMessages = properties.agent().memoryMaxMessages();
        return AiServices.builder(InvestigatorAgent.class)
                .chatModel(chatModel)
                .tools(tools)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(maxMessages)
                        .build())
                // Req 13.6: Tool_Call_Budget plus 2. LangChain4j counts every model response,
                // the final answer included, so the model gets budget + 1 tool rounds (the
                // budget plus the one the guard refuses) and one response to return the report.
                .maxToolCallingRoundTrips(properties.budget().maxToolCalls() + 2)
                .build();
    }

    /** Stand-in used when no Chat_Model bean exists; every call fails with {@link #NO_CHAT_MODEL}. */
    static final class UnavailableChatModel implements ChatModel {

        @Override
        public ChatResponse doChat(ChatRequest request) {
            throw new IllegalStateException(NO_CHAT_MODEL);
        }
    }
}
