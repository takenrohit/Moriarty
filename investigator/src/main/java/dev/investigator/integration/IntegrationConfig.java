package dev.investigator.integration;

import dev.investigator.config.InvestigatorProperties;
import dev.investigator.integration.changes.ChangeClient;
import dev.investigator.integration.logs.LogClient;
import dev.investigator.integration.metrics.MetricsClient;
import dev.investigator.integration.mock.MockChangeClient;
import dev.investigator.integration.mock.MockLogClient;
import dev.investigator.integration.mock.MockMetricsClient;
import dev.investigator.integration.mock.MockTraceClient;
import dev.investigator.integration.mock.ScenarioAnchorProvider;
import dev.investigator.integration.mock.ScopeScenarioAnchorProvider;
import dev.investigator.integration.traces.TraceClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Integration_Selector: registers the Mock_Integrations client of each integration only when no other
 * bean of its interface exists, so exactly one {@link MetricsClient}, {@link LogClient},
 * {@link TraceClient} and {@link ChangeClient} is present (Req 7.1 to 7.4, CP-45).
 *
 * <p>The real clients are {@code @Component}s annotated {@code @Profile("real")} and
 * {@code @ConditionalOnIntegrationEndpoint}. Ordering is safe: component scanning registers every
 * scanned bean definition, real clients included, while configuration classes are parsed, and the
 * {@code @ConditionalOnMissingBean} conditions of the {@code @Bean} methods below are evaluated later,
 * when those methods are loaded (CP-61). A test that registers a client of its own also replaces the mock.
 */
@Configuration(proxyBeanMethods = false)
public class IntegrationConfig {

    @Bean
    @ConditionalOnMissingBean(ScenarioAnchorProvider.class)
    ScopeScenarioAnchorProvider scenarioAnchorProvider(InvestigatorProperties properties) {
        return new ScopeScenarioAnchorProvider(properties);
    }

    @Bean
    @ConditionalOnMissingBean(MetricsClient.class)
    MockMetricsClient mockMetricsClient(ScenarioAnchorProvider anchors, InvestigatorProperties properties) {
        return new MockMetricsClient(anchors, properties);
    }

    @Bean
    @ConditionalOnMissingBean(LogClient.class)
    MockLogClient mockLogClient(ScenarioAnchorProvider anchors, InvestigatorProperties properties) {
        return new MockLogClient(anchors, properties);
    }

    @Bean
    @ConditionalOnMissingBean(TraceClient.class)
    MockTraceClient mockTraceClient(ScenarioAnchorProvider anchors, InvestigatorProperties properties) {
        return new MockTraceClient(anchors, properties);
    }

    @Bean
    @ConditionalOnMissingBean(ChangeClient.class)
    MockChangeClient mockChangeClient(ScenarioAnchorProvider anchors) {
        return new MockChangeClient(anchors);
    }
}
