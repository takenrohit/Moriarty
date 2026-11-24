package dev.investigator.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.investigator.config.JacksonConfig;
import dev.investigator.testsupport.DomainGenerators;
import dev.investigator.testsupport.PropertySeeds;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class DomainJsonRoundTripPropertyTest {

    /** ISO-8601 UTC instant as written by the JSON_Codec: date, time, optional fraction, {@code Z}. */
    private static final Pattern ISO_UTC_INSTANT =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z$");

    private static final ObjectMapper CODEC = JacksonConfig.jsonCodec();

    /** One generator per Domain_Model record named by Property 1. */
    private static final List<Function<SplittableRandom, ? extends Record>> GENERATORS = List.of(
            DomainGenerators::incident,
            DomainGenerators::evidence,
            DomainGenerators::evidenceRef,
            DomainGenerators::timelineEntry,
            DomainGenerators::incidentTimeline,
            DomainGenerators::hypothesis,
            DomainGenerators::recommendedAction,
            DomainGenerators::investigation,
            DomainGenerators::investigationReport);

    // Feature: core-investigator, Property 1: Domain JSON round trip
    // Validates: Requirements 3.4, 3.5
    @ParameterizedTest(name = "seed={0}")
    @MethodSource("dev.investigator.testsupport.PropertySeeds#seeds")
    void domainRecordsSurviveJsonRoundTrip(long seed) {
        PropertySeeds.check(seed, r -> {
            for (Function<SplittableRandom, ? extends Record> generator : GENERATORS) {
                Record original = generator.apply(r);
                String json = CODEC.writeValueAsString(original);

                Record decoded = CODEC.readValue(json, original.getClass());
                assertThat(decoded)
                        .as("%s round trip of %s", original.getClass().getSimpleName(), json)
                        .isEqualTo(original);

                assertInstantsAreIsoUtc(original, CODEC.readTree(json), original.getClass().getSimpleName());
            }
        });
    }

    /**
     * Walks the record and its JSON tree together. Every non-null {@code Instant} component must be written
     * as an ISO-8601 string ending in {@code Z} that parses back to the same instant (Req 3.4).
     */
    private static void assertInstantsAreIsoUtc(Object value, JsonNode node, String path) throws Exception {
        if (value instanceof Record record) {
            for (RecordComponent component : record.getClass().getRecordComponents()) {
                Object componentValue = component.getAccessor().invoke(record);
                JsonNode componentNode = node.get(component.getName());
                String componentPath = path + "." + component.getName();
                if (componentValue instanceof Instant instant) {
                    assertThat(componentNode).as(componentPath).isNotNull();
                    assertThat(componentNode.isTextual()).as("%s is a JSON string: %s", componentPath, componentNode)
                            .isTrue();
                    assertThat(componentNode.textValue()).as(componentPath).matches(ISO_UTC_INSTANT);
                    assertThat(Instant.parse(componentNode.textValue())).as(componentPath).isEqualTo(instant);
                } else {
                    assertInstantsAreIsoUtc(componentValue, componentNode, componentPath);
                }
            }
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                assertInstantsAreIsoUtc(list.get(i), node.get(i), path + "[" + i + "]");
            }
        }
    }
}
