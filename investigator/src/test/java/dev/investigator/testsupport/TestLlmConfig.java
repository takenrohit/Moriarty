package dev.investigator.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Registers the {@link ScriptedChatModel} as the only Chat_Model bean for tests that run with
 * {@code investigator.llm.enabled=false} (imported by {@link InvestigatorSpringBootTest}).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestLlmConfig {

    @Bean
    ScriptedChatModel scriptedChatModel() {
        return new ScriptedChatModel();
    }
}
