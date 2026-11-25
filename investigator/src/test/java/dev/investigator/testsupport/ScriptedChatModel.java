package dev.investigator.testsupport;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scripted_Chat_Model: a {@link ChatModel} that answers each request with the next scripted step and
 * never touches the network (Req 19.2, 19.4).
 *
 * <p>A step is a {@code Function<ChatRequest, ChatResponse>}, so it can read the tool results already
 * in the conversation (for example to learn the Evidence_IDs assigned at runtime) before deciding what
 * to return. When the script is empty, the model returns the final answer {@value #DEFAULT_FINAL_ANSWER}.
 * Every request is recorded for assertions.
 *
 * <p>The bean is shared by all tests in the cached Spring context: call {@link #reset()} before
 * scripting a test. Safe for use from the orchestrator's virtual threads.
 */
public final class ScriptedChatModel implements ChatModel {

    /** Final answer returned when no scripted step is left. */
    public static final String DEFAULT_FINAL_ANSWER = "{}";

    private static final AtomicLong TOOL_CALL_IDS = new AtomicLong();
    private static final Pattern EVIDENCE_ID_FIELD = Pattern.compile("\"evidenceId\"\\s*:\\s*\"(ev-[^\"]+)\"");

    private final ConcurrentLinkedQueue<Function<ChatRequest, ChatResponse>> steps = new ConcurrentLinkedQueue<>();
    private final List<ChatRequest> requests = new CopyOnWriteArrayList<>();

    @Override
    public ChatResponse doChat(ChatRequest request) {
        requests.add(request);
        Function<ChatRequest, ChatResponse> step = steps.poll();
        if (step == null) {
            return finalAnswer(DEFAULT_FINAL_ANSWER).apply(request);
        }
        return Objects.requireNonNull(step.apply(request), "a scripted step returned null");
    }

    /** Appends steps to the script, in order. */
    @SafeVarargs
    public final ScriptedChatModel enqueue(Function<ChatRequest, ChatResponse>... newSteps) {
        Arrays.stream(newSteps).map(s -> Objects.requireNonNull(s, "step")).forEach(steps::add);
        return this;
    }

    /** Clears the remaining script and the recorded requests. */
    public ScriptedChatModel reset() {
        steps.clear();
        requests.clear();
        return this;
    }

    /** Every request received so far, oldest first. */
    public List<ChatRequest> requests() {
        return List.copyOf(requests);
    }

    /** Number of scripted steps not yet used. */
    public int remainingSteps() {
        return steps.size();
    }

    /** A step that ends the tool loop with {@code text} as the model's final answer. */
    public static Function<ChatRequest, ChatResponse> finalAnswer(String text) {
        Objects.requireNonNull(text, "text");
        return request -> ChatResponse.builder()
                .aiMessage(AiMessage.from(text))
                .finishReason(FinishReason.STOP)
                .build();
    }

    /** A step that asks for one tool call with the given JSON arguments. */
    public static Function<ChatRequest, ChatResponse> toolCall(String toolName, String argumentsJson) {
        ToolExecutionRequest call = call(toolName, argumentsJson);
        return toolCalls(call);
    }

    /** A step that asks for the given tool calls in one round trip. */
    public static Function<ChatRequest, ChatResponse> toolCalls(ToolExecutionRequest... calls) {
        List<ToolExecutionRequest> list = List.of(calls);
        return request -> ChatResponse.builder()
                .aiMessage(AiMessage.from(list))
                .finishReason(FinishReason.TOOL_EXECUTION)
                .build();
    }

    /** A tool execution request with a unique ID. */
    public static ToolExecutionRequest call(String toolName, String argumentsJson) {
        return ToolExecutionRequest.builder()
                .id("call-" + TOOL_CALL_IDS.incrementAndGet())
                .name(Objects.requireNonNull(toolName, "toolName"))
                .arguments(Objects.requireNonNull(argumentsJson, "argumentsJson"))
                .build();
    }

    /** The tool results in the request's conversation, oldest first. */
    public static List<ToolExecutionResultMessage> toolResults(ChatRequest request) {
        List<ToolExecutionResultMessage> results = new ArrayList<>();
        for (ChatMessage message : request.messages()) {
            if (message instanceof ToolExecutionResultMessage result) {
                results.add(result);
            }
        }
        return results;
    }

    /** The most recent tool result in the request's conversation, if any. */
    public static Optional<ToolExecutionResultMessage> lastToolResult(ChatRequest request) {
        List<ToolExecutionResultMessage> results = toolResults(request);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(results.size() - 1));
    }

    /**
     * The Evidence_IDs named by {@code "evidenceId"} fields in one tool result, in order of appearance
     * and without duplicates. Error and not-found results name none.
     */
    public static List<String> evidenceIds(ToolExecutionResultMessage result) {
        Set<String> ids = new LinkedHashSet<>();
        Matcher m = EVIDENCE_ID_FIELD.matcher(result.text());
        while (m.find()) {
            ids.add(m.group(1));
        }
        return List.copyOf(ids);
    }

    /** The Evidence_IDs in all tool results of the request's conversation, oldest first, without duplicates. */
    public static List<String> evidenceIds(ChatRequest request) {
        Set<String> ids = new LinkedHashSet<>();
        toolResults(request).forEach(r -> ids.addAll(evidenceIds(r)));
        return List.copyOf(ids);
    }

    /** The Evidence_IDs in the tool results of the request's conversation for one tool, oldest first. */
    public static List<String> evidenceIds(ChatRequest request, String toolName) {
        Set<String> ids = new LinkedHashSet<>();
        toolResults(request).stream().filter(r -> toolName.equals(r.toolName()))
                .forEach(r -> ids.addAll(evidenceIds(r)));
        return List.copyOf(ids);
    }
}
