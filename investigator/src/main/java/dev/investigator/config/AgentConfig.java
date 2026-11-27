package dev.investigator.config;

import dev.investigator.investigation.BlankAiMessageChatModel;
import dev.investigator.investigation.InvestigationContextRegistry;
import dev.investigator.investigation.InvestigatorAgent;
import dev.investigator.investigation.TokenBudgetChatModel;
import dev.investigator.tools.ObservabilityTools;
import dev.investigator.tools.ToolGuard;
import dev.investigator.tools.ToolResults;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import java.util.Arrays;
import java.util.List;
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
 *
 * <p>Tool calls with arguments LangChain4j cannot parse, and calls to tool names that do not exist,
 * return a {@link ToolResults#error} JSON to the model instead of failing the investigation.
 */
@Configuration(proxyBeanMethods = false)
public class AgentConfig {

    static final String NO_CHAT_MODEL =
            "No ChatModel bean is available. Set investigator.llm.enabled=true and LLM_API_KEY,"
                    + " or register a ChatModel bean.";

    private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);

    static final String UNKNOWN_TOOL = "unknown tool";

    @Bean
    InvestigatorAgent investigatorAgent(ObjectProvider<ChatModel> chatModels, ObservabilityTools tools,
            ToolGuard guard, InvestigatorProperties properties, InvestigationContextRegistry contexts) {
        ChatModel chatModel = chatModels.getIfAvailable();
        if (chatModel == null) {
            log.warn("{} Investigations will fail until a ChatModel is configured.", NO_CHAT_MODEL);
            chatModel = new UnavailableChatModel();
        }
        // Blank model answers in the history would make Bedrock reject the correction request.
        chatModel = new BlankAiMessageChatModel(chatModel);
        // Token_Budget per investigation (Req 6.5): refuses requests once the budget is reached.
        chatModel = new TokenBudgetChatModel(chatModel, contexts);
        int maxMessages = properties.agent().memoryMaxMessages();
        String unknownToolResult = ToolResults.error(UNKNOWN_TOOL, "use one of: " + String.join(", ", toolNames()));
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
                // Malformed arguments never reach the tool method; the guard still counts the call
                // and the model gets an error result instead of the whole run failing (CP-35 e).
                .toolArgumentsErrorHandler((error, context) -> {
                    String toolName = context.toolExecutionRequest().name();
                    log.warn("tool arguments could not be parsed tool_name={} error={}", toolName,
                            error.getClass().getSimpleName());
                    return ToolErrorHandlerResult.text(guard.malformedArguments(context.memoryId(), toolName));
                })
                // An unknown tool name gets an error result listing the real tools; the name the model
                // sent is not echoed.
                .hallucinatedToolNameStrategy(request -> {
                    log.warn("model called an unknown tool");
                    return ToolExecutionResultMessage.from(request, unknownToolResult);
                })
                .build();
    }

    /** Names of the {@code @Tool} methods of {@link ObservabilityTools}, sorted. */
    static List<String> toolNames() {
        return Arrays.stream(ObservabilityTools.class.getMethods())
                .filter(m -> m.isAnnotationPresent(Tool.class))
                .map(m -> {
                    String name = m.getAnnotation(Tool.class).name();
                    return name == null || name.isBlank() ? m.getName() : name;
                })
                .sorted()
                .toList();
    }

    /** Stand-in used when no Chat_Model bean exists; every call fails with {@link #NO_CHAT_MODEL}. */
    static final class UnavailableChatModel implements ChatModel {

        @Override
        public ChatResponse doChat(ChatRequest request) {
            throw new IllegalStateException(NO_CHAT_MODEL);
        }
    }
}
