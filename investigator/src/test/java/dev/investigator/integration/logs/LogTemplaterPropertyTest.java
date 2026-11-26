// Feature: real-integrations, Property 3: Log clustering groups variable-only differences
package dev.investigator.integration.logs;

import static org.assertj.core.api.Assertions.assertThat;

import dev.investigator.testsupport.LogMessageGenerators;
import dev.investigator.testsupport.LogMessageGenerators.Template;
import dev.investigator.testsupport.PropertySeeds;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Property 3 over {@link LogTemplater#template}: two messages rendered from one template, so differing only
 * in numbers, UUIDs, hexadecimal IDs, quoted strings and timestamps, get the same template (the template's
 * words and separators with a placeholder per slot); a message whose template differs in one word gets a
 * different template.
 */
class LogTemplaterPropertyTest {

    /** **Validates: Requirements 4.4, 4.6** */
    @ParameterizedTest(name = "seed={0}")
    @MethodSource("dev.investigator.testsupport.PropertySeeds#seeds")
    void variableOnlyDifferencesShareATemplateAndWordsDoNot(long seed) {
        PropertySeeds.check(seed, r -> {
            Template template = LogMessageGenerators.template(r);
            String first = LogMessageGenerators.render(r, template);
            String second = LogMessageGenerators.render(r, template);
            Template changed = LogMessageGenerators.withChangedWord(r, template);
            String other = LogMessageGenerators.render(r, changed);

            String expected = LogMessageGenerators.expected(template);
            assertThat(LogTemplater.template(first)).as("template of %s", first).isEqualTo(expected);
            assertThat(LogTemplater.template(second)).as("template of %s", second).isEqualTo(expected);
            assertThat(LogTemplater.template(other)).as("template of %s", other)
                    .isEqualTo(LogMessageGenerators.expected(changed))
                    .isNotEqualTo(expected);
        });
    }
}
