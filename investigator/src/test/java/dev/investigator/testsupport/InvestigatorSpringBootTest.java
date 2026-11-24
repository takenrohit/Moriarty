package dev.investigator.testsupport;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * {@code @SpringBootTest} with the production Chat_Model disabled, so tests need no LLM credentials
 * or network access. All test classes using it share one cached application context.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(properties = "investigator.llm.enabled=false")
public @interface InvestigatorSpringBootTest {
}
