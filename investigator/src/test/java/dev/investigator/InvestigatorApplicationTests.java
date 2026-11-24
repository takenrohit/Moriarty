package dev.investigator;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Checks that the application context starts with the default configuration,
 * which needs no PostgreSQL, no Redis and no LLM credentials.
 */
@SpringBootTest
class InvestigatorApplicationTests {

    @Test
    void contextLoads() {
    }
}
