// Feature: real-integrations, Property 4: Tempo parsing preserves traces
package dev.investigator.integration.traces;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.investigator.testsupport.PropertySeeds;
import dev.investigator.testsupport.ResponseGenerators;
import dev.investigator.testsupport.ResponseGenerators.TempoSearch;
import dev.investigator.testsupport.ResponseGenerators.TempoTrace;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Property 4 over {@link TempoTraceClient#parse}: every trace of the body becomes one {@link TraceRecord} with
 * the same trace ID, start, error flag and set of span services; traces are in start order, the earliest
 * {@code limit} are kept, and {@code truncated} is set exactly when the body held more than {@code limit}
 * (the client asks Tempo for {@code limit + 1}).
 */
class TempoParsingPropertyTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** **Validates: Requirements 5.3, 5.6** */
    @ParameterizedTest(name = "seed={0}")
    @MethodSource("dev.investigator.testsupport.PropertySeeds#seeds")
    void parsingPreservesTraceCountErrorFlagsAndServices(long seed) {
        PropertySeeds.check(seed, r -> {
            int limit = r.nextInt(4) == 0 ? 50 : r.nextInt(1, 30);
            TempoSearch search = ResponseGenerators.tempoSearch(r, limit);

            TraceSearchResult result = TempoTraceClient.parse(JSON.readTree(search.body()), limit);

            int total = search.traces().size();
            List<TempoTrace> kept = search.traces().subList(0, Math.min(total, limit));
            assertThat(result.truncated()).isEqualTo(total > limit);
            assertThat(result.traces()).hasSize(kept.size())
                    .isSortedAccordingTo(Comparator.comparing(TraceRecord::start));
            for (int i = 0; i < kept.size(); i++) {
                TempoTrace expected = kept.get(i);
                TraceRecord actual = result.traces().get(i);
                assertThat(actual.traceId()).isEqualTo(expected.traceId());
                assertThat(actual.start()).isEqualTo(expected.start());
                assertThat(actual.error()).as("error flag of %s", expected.traceId()).isEqualTo(expected.error());
                assertThat(actual.spans().stream().map(SpanRecord::service).collect(Collectors.toSet()))
                        .as("services of %s", expected.traceId()).isEqualTo(expected.services());
            }
        });
    }
}
