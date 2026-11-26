package dev.investigator.integration.http;

import dev.investigator.config.IntegrationProperties;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/** Condition behind {@link ConditionalOnIntegrationEndpoint}. */
class OnIntegrationEndpointCondition extends SpringBootCondition {

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Map<String, Object> attributes =
                metadata.getAnnotationAttributes(ConditionalOnIntegrationEndpoint.class.getName());
        String integration = (String) attributes.get("value");
        String property = IntegrationProperties.baseUrlProperty(integration);
        ConditionMessage.Builder message =
                ConditionMessage.forCondition(ConditionalOnIntegrationEndpoint.class, integration);
        if (StringUtils.hasText(context.getEnvironment().getProperty(property))) {
            return ConditionOutcome.match(message.found("property").items(property));
        }
        return ConditionOutcome.noMatch(message.didNotFind("non-blank property").items(property));
    }
}
