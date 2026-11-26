package dev.investigator.integration.http;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/**
 * Matches when {@code investigator.integrations.<value>.base-url} is set to a non-blank value.
 *
 * <p>Used instead of a plain {@code @ConditionalOnProperty} because {@code application-real.yml} binds
 * the endpoint as {@code ${PROMETHEUS_URL:}}: an unset variable yields an empty but present property,
 * which {@code @ConditionalOnProperty} would treat as configured. Blank therefore means "not configured"
 * and the mock fallback is used (Req 7.2, CP-61).
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnIntegrationEndpointCondition.class)
public @interface ConditionalOnIntegrationEndpoint {

    /** Integration name: {@code metrics}, {@code logs}, {@code traces} or {@code changes}. */
    String value();
}
