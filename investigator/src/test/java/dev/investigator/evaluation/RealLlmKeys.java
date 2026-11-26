package dev.investigator.evaluation;

import java.util.stream.Stream;

/**
 * JUnit {@code @EnabledIf} condition for the Real_LLM_Evaluation: true when {@code LLM_API_KEY} or
 * {@code AWS_BEARER_TOKEN_BEDROCK} holds a non-blank value (Req 11.13). JUnit evaluates it before the
 * Spring context starts, so a missing key skips the class instead of failing {@code LlmConfig}.
 */
final class RealLlmKeys {

    private RealLlmKeys() {
    }

    static boolean present() {
        return Stream.of("LLM_API_KEY", "AWS_BEARER_TOKEN_BEDROCK")
                .map(System::getenv)
                .anyMatch(value -> value != null && !value.isBlank());
    }
}
