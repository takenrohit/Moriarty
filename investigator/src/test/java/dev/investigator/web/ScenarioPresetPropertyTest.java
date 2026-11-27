// Feature: investigator-web-ui, Property 3: Scenario presets prefill a round-tripping form
package dev.investigator.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.investigator.api.MockAlertRequest;
import dev.investigator.integration.mock.ScenarioKind;
import dev.investigator.testsupport.InvestigatorSpringBootTest;
import dev.investigator.testsupport.PropertySeeds;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Property 3: for each of the four Scenario_Presets and any case/separator variant of its key,
 * {@code GET /incidents/new?scenario=<variant>} prefills the alert, service and severity inputs with the
 * preset's values and selects that preset, and submitting the rendered form values gives a {@code scenario}
 * label for which {@link ScenarioKind#fromLabel} returns the preset's kind.
 *
 * <p>Uses the shared context's MockMvc: the GET page reads no store, so other tests' state does not matter.
 *
 * <p><b>Validates: Requirements 3.1, 3.2</b>
 */
@InvestigatorSpringBootTest
@AutoConfigureMockMvc
class ScenarioPresetPropertyTest {

    private static final Pattern OPTION = Pattern.compile("<option\\b[^>]*>");
    private static final Pattern VALUE = Pattern.compile("\\bvalue=\"([^\"]*)\"");
    private static final Pattern SELECTED = Pattern.compile("\\bselected\\b");
    private static final char[] SEPARATORS = {'-', '_', ' '};

    @Autowired
    private MockMvc mvc;

    @ParameterizedTest(name = "seed={0}")
    @MethodSource("dev.investigator.testsupport.PropertySeeds#seeds")
    void presetsPrefillARoundTrippingForm(long seed) {
        PropertySeeds.check(seed, random -> {
            assertThat(ScenarioPreset.ALL).extracting(ScenarioPreset::kind)
                    .containsExactlyInAnyOrder(ScenarioKind.values());
            for (ScenarioPreset preset : ScenarioPreset.ALL) {
                String variant = variant(preset.key(), random);
                String html = mvc.perform(get("/incidents/new").param("scenario", variant))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

                // (a) The page is prefilled with the preset and selects it.
                assertThat(inputValue(html, "alert")).as("alert for %s", variant).isEqualTo(preset.alert());
                assertThat(inputValue(html, "service")).as("service for %s", variant).isEqualTo(preset.service());
                assertThat(inputValue(html, "severity")).as("severity for %s", variant).isEqualTo(preset.severity());
                String selected = selectedScenario(html);
                assertThat(selected).as("selected preset for %s", variant).isEqualTo(preset.key());

                // (b) Submitting the rendered values round-trips the scenario label to the preset's kind.
                AlertForm submitted = new AlertForm();
                submitted.setAlert(inputValue(html, "alert"));
                submitted.setService(inputValue(html, "service"));
                submitted.setSeverity(inputValue(html, "severity"));
                submitted.setDetectedAt(inputValue(html, "detectedAt"));
                submitted.setScenario(selected);
                MockAlertRequest request = submitted.toRequest();
                String label = request.labels().get(ScenarioKind.LABEL);
                assertThat(ScenarioKind.fromLabel(label)).as("kind of label %s", label).isEqualTo(preset.kind());
                assertThat(request.labels()).containsEntry("environment", "production");
                assertThat(request.alert()).isEqualTo(preset.alert());
                assertThat(request.service()).isEqualTo(preset.service());
                assertThat(request.severity()).isEqualTo(preset.severity());
            }
        });
    }

    /**
     * The key with each letter's case and each separator drawn at random, optionally padded with spaces
     * (all matched like the {@code scenario} label); {@code noisy-neighbour} sometimes uses the US spelling.
     */
    private static String variant(String key, SplittableRandom random) {
        String base = key.equals("noisy-neighbour") && random.nextBoolean() ? "noisy-neighbor" : key;
        StringBuilder out = new StringBuilder();
        if (random.nextInt(4) == 0) {
            out.append(' ');
        }
        for (char c : base.toCharArray()) {
            if (c == '-') {
                out.append(SEPARATORS[random.nextInt(SEPARATORS.length)]);
            } else if (random.nextBoolean()) {
                out.append(Character.toUpperCase(c));
            } else {
                out.append(c);
            }
        }
        if (random.nextInt(4) == 0) {
            out.append(' ');
        }
        return out.toString();
    }

    /** The {@code value} of the input whose id is {@code id}; fails if the input or its value is missing. */
    private static String inputValue(String html, String id) {
        Matcher input = Pattern.compile("<input\\b[^>]*\\bid=\"" + Pattern.quote(id) + "\"[^>]*>").matcher(html);
        assertThat(input.find()).as("input %s", id).isTrue();
        Matcher value = VALUE.matcher(input.group());
        assertThat(value.find()).as("value of input %s", id).isTrue();
        return value.group(1);
    }

    /** The value of the single selected option of the {@code scenario} select. */
    private static String selectedScenario(String html) {
        int start = html.indexOf("<select id=\"scenario\"");
        assertThat(start).as("scenario select").isNotNegative();
        int end = html.indexOf("</select>", start);
        assertThat(end).as("scenario select end").isPositive();
        List<String> selected = new ArrayList<>();
        int options = 0;
        Matcher option = OPTION.matcher(html.substring(start, end));
        while (option.find()) {
            options++;
            String tag = option.group();
            String withoutData = tag.replaceAll("\\sdata-[a-z-]+=\"[^\"]*\"", "");
            if (SELECTED.matcher(withoutData.replaceAll("\\svalue=\"[^\"]*\"", "")).find()) {
                Matcher value = VALUE.matcher(tag);
                assertThat(value.find()).as("value of %s", tag).isTrue();
                selected.add(value.group(1));
            }
        }
        assertThat(options).as("preset options").isEqualTo(ScenarioPreset.ALL.size());
        assertThat(selected).as("selected options").hasSize(1);
        return selected.get(0);
    }
}
