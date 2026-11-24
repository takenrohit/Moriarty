package dev.investigator.alert;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Example tests for {@link Fingerprints} (Req 5.5, 5.7). */
class FingerprintsTest {

    @Test
    void plainInputsHashTheUnescapedCanonicalString() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("region", "ap-south-1");
        labels.put("environment", "production");
        assertThat(Fingerprints.canonical("HighErrorRate", "checkout-api", labels))
                .isEqualTo("HighErrorRate\u0000checkout-api\u0000environment=production\u0000region=ap-south-1");
        assertThat(Fingerprints.of("HighErrorRate", "checkout-api", labels)).matches("[0-9a-f]{64}");
    }

    @Test
    void labelOrderDoesNotMatter() {
        Map<String, String> forward = new LinkedHashMap<>();
        forward.put("a", "1");
        forward.put("b", "2");
        Map<String, String> reverse = new LinkedHashMap<>();
        reverse.put("b", "2");
        reverse.put("a", "1");
        assertThat(Fingerprints.of("x", "y", forward)).isEqualTo(Fingerprints.of("x", "y", reverse));
    }

    @Test
    void separatorAndEqualsCharactersCannotForgeAnotherLabelSet() {
        assertThat(Fingerprints.of("x", "y", Map.of("a=b", "c")))
                .isNotEqualTo(Fingerprints.of("x", "y", Map.of("a", "b=c")));
        assertThat(Fingerprints.of("x", "y", Map.of("a", "b\u0000c=d")))
                .isNotEqualTo(Fingerprints.of("x", "y", Map.of("a", "b", "c", "d")));
        assertThat(Fingerprints.of("x\u0000y", "z", Map.of()))
                .isNotEqualTo(Fingerprints.of("x", "y\u0000z", Map.of()));
        assertThat(Fingerprints.of("x", "y", null)).isEqualTo(Fingerprints.of("x", "y", Map.of()));
    }

    @Test
    void unpairedSurrogateDiffersFromQuestionMark() {
        assertThat(Fingerprints.of("\uD800", "y", Map.of())).isNotEqualTo(Fingerprints.of("?", "y", Map.of()));
    }
}
