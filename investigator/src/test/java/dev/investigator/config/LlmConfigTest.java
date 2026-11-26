package dev.investigator.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.model.bedrock.BedrockChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Chat_Model provider selection (reliability Req 11). Building either model makes no network call, so
 * these tests only construct the beans.
 */
class LlmConfigTest {

    private static final String BEDROCK_KEY = "bedrock-test-key-123";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class, LlmConfig.class);

    private ApplicationContextRunner bedrock() {
        return runner.withPropertyValues("investigator.llm.provider=bedrock",
                "investigator.llm.model=us.amazon.nova-pro-v1:0");
    }

    @Test
    void openAiIsTheDefaultProvider() {
        runner.withPropertyValues("investigator.llm.api-key=sk-test").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(ChatModel.class)).isInstanceOf(OpenAiChatModel.class);
        });
    }

    @Test
    void openAiWithoutApiKeyFailsNamingLlmApiKey() {
        runner.run(ctx -> assertThat(ctx).hasFailed()
                .getFailure().rootCause().hasMessageContaining("LLM_API_KEY"));
    }

    @Test
    void bedrockWithApiKeyBuildsBedrockChatModelWithTheConfiguredParameters() {
        bedrock().withPropertyValues("investigator.llm.bedrock.api-key=" + BEDROCK_KEY,
                        "investigator.llm.bedrock.region=eu-west-1", "investigator.llm.temperature=0.2",
                        "investigator.llm.max-output-tokens=2048")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    ChatModel model = ctx.getBean(ChatModel.class);
                    assertThat(model).isInstanceOf(BedrockChatModel.class);
                    assertThat(model.defaultRequestParameters().modelName()).isEqualTo("us.amazon.nova-pro-v1:0");
                    assertThat(model.defaultRequestParameters().temperature()).isEqualTo(0.2);
                    assertThat(model.defaultRequestParameters().maxOutputTokens()).isEqualTo(2048);
                });
    }

    @Test
    void bedrockStartsWithoutLlmApiKeyUsingTheDefaultCredentialChain() {
        bedrock().withPropertyValues("investigator.llm.api-key=", "investigator.llm.bedrock.api-key=").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(ChatModel.class)).isInstanceOf(BedrockChatModel.class);
            assertThat(ctx.getBean(ChatModel.class).defaultRequestParameters().maxOutputTokens()).isEqualTo(4096);
        });
    }

    @Test
    void unknownProviderFailsNamingLlmProvider() {
        runner.withPropertyValues("investigator.llm.provider=azure").run(ctx -> assertThat(ctx).hasFailed()
                .getFailure().hasStackTraceContaining("LLM_PROVIDER"));
    }

    @Test
    void bedrockWithBlankModelFailsNamingLlmModel() {
        bedrock().withPropertyValues("investigator.llm.model=").run(ctx -> assertThat(ctx).hasFailed()
                .getFailure().hasStackTraceContaining("LLM_MODEL"));
    }

    @Test
    void bedrockWithInvalidRegionFailsNamingAwsRegion() {
        bedrock().withPropertyValues("investigator.llm.bedrock.region=mars-north-9").run(ctx -> assertThat(ctx)
                .hasFailed().getFailure().rootCause().hasMessageContaining("AWS_REGION"));
    }

    @Test
    void toStringMasksTheBedrockKey() {
        bedrock().withPropertyValues("investigator.llm.bedrock.api-key=" + BEDROCK_KEY).run(ctx -> {
            String text = ctx.getBean(LlmProperties.class).toString();
            assertThat(text).doesNotContain(BEDROCK_KEY).contains("apiKey=" + LlmProperties.MASK);
        });
    }
}
