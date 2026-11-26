// Feature: real-integrations, Property 2: Loki parsing preserves entries
package dev.investigator.integration.logs;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.investigator.testsupport.PropertySeeds;
import dev.investigator.testsupport.ResponseGenerators;
import dev.investigator.testsupport.ResponseGenerators.LokiStreams;
import java.util.Comparator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Property 2 over {@link LokiLogClient#parse}: every value of every stream becomes one {@link LogEntry} with
 * the same timestamp, level and line, entries are in time order, the earliest {@code limit} are kept, and
 * {@code truncated} is set exactly when the body held more than {@code limit} entries (the client asks Loki
 * for {@code limit + 1}, so this is exactly when Loki's returned count reaches the requested limit).
 */
class LokiParsingPropertyTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** **Validates: Requirements 4.2, 4.3, 4.7** */
    @ParameterizedTest(name = "seed={0}")
    @MethodSource("dev.investigator.testsupport.PropertySeeds#seeds")
    void parsingPreservesEveryEntryInTimeOrder(long seed) {
        PropertySeeds.check(seed, r -> {
            int limit = r.nextInt(4) == 0 ? 500 : r.nextInt(1, 60);
            LokiStreams streams = ResponseGenerators.lokiStreams(r, limit);

            LogSearchResult result = LokiLogClient.parse(JSON.readTree(streams.body()), limit);

            int total = streams.entries().size();
            assertThat(result.truncated()).isEqualTo(total > limit);
            assertThat(result.entries()).hasSize(Math.min(total, limit))
                    .isSortedAccordingTo(Comparator.comparing(LogEntry::at))
                    .containsExactlyElementsOf(streams.entries().subList(0, Math.min(total, limit)));
        });
    }
}
