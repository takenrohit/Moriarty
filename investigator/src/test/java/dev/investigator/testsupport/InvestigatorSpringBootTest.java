package dev.investigator.testsupport;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * {@code @SpringBootTest} with the production Chat_Model disabled and the {@link ScriptedChatModel}
 * registered through {@link TestLlmConfig}, so tests need no LLM credentials or network access. Rate
 * limits are raised so the shared context never trips them. All
 * test classes using it share one cached application context.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(properties = {
        "investigator.llm.enabled=false",
        // The shared context runs many investigations and tool calls; rate limits are tested directly.
        "investigator.rate-limit.investigations-per-service-per-hour=1000000",
        "investigator.rate-limit.tool-calls-per-minute.metrics=1000000",
        "investigator.rate-limit.tool-calls-per-minute.logs=1000000",
        "investigator.rate-limit.tool-calls-per-minute.traces=1000000",
        "investigator.rate-limit.tool-calls-per-minute.changes=1000000",
        "investigator.rate-limit.tool-calls-per-minute.runbooks=1000000"})
@Import(TestLlmConfig.class)
public @interface InvestigatorSpringBootTest {
}
