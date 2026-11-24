package dev.investigator;

import dev.investigator.testsupport.InvestigatorSpringBootTest;
import org.junit.jupiter.api.Test;

/**
 * Checks that the application context starts with the default configuration,
 * which needs no PostgreSQL, no Redis and no LLM credentials.
 */
@InvestigatorSpringBootTest
class InvestigatorApplicationTests {

    @Test
    void contextLoads() {
    }
}
