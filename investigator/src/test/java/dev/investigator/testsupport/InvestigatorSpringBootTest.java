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
 * registered through {@link TestLlmConfig}, so tests need no LLM credentials or network access. All
 * test classes using it share one cached application context.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(properties = "investigator.llm.enabled=false")
@Import(TestLlmConfig.class)
public @interface InvestigatorSpringBootTest {
}
