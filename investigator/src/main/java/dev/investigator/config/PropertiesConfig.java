package dev.investigator.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the typed configuration records. {@link LlmProperties} is registered here rather than in
 * {@link LlmConfig}, so it is available even when the production Chat_Model is disabled.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({InvestigatorProperties.class, LlmProperties.class})
public class PropertiesConfig {
}
