// Feature: investigator-web-ui, Property 4: Form submission matches Alert_Intake validation and preserves rejected input
package dev.investigator.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.investigator.domain.Incident;
import dev.investigator.domain.InvestigationStatus;
import dev.investigator.integration.mock.ScenarioKind;
import dev.investigator.store.IncidentRepository;
import dev.investigator.store.InvestigationRepository;
import dev.investigator.testsupport.InvestigatorSpringBootTest;
import dev.investigator.testsupport.PropertySeeds;
import dev.investigator.testsupport.ScriptedChatModel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.HtmlUtils;

/**
 * Property 4: for any generated New_Alert_Form submission (blank, absent, long and HTML-metacharacter field
 * values, severities in any case or unknown, {@code detectedAt} with or without an offset or garbage),
 * {@code POST /incidents} accepts exactly when {@code POST /api/v1/alerts} accepts the same alert, severity,
 * service and {@code detectedAt} with the labels {@code scenario=<preset key>} and {@code environment=production}.
 * An accepted form redirects to {@code /incidents/<id>} of a stored Incident built from those fields; a rejected
 * one answers 400, lists the same rejected fields as the JSON error body and re-renders every entered value,
 * HTML-escaped, in its input.
 *
 * <p>Accepted alert names carry a per-case token, so each accepted form creates its own Incident rather than
 * attaching to an open one with the same Fingerprint. The JSON post of the same fields then attaches to it or,
 * if its investigation has already ended, starts another; both are awaited after each case.
 *
 * <p><b>Validates: Requirements 3.4, 3.5, 3.6</b>
 */
@InvestigatorSpringBootTest
@AutoConfigureMockMvc
class AlertFormPropertyTest {

    private static final Pattern REDIRECT = Pattern.compile("/incidents/(inc-[0-9a-f]{12})");
    private static final Pattern ERROR_FIELD = Pattern.compile("<span class=\"ev\">([^<]*)</span>: <span>[^<]+</span>");
    private static final Pattern VALUE = Pattern.compile("\\bvalue=\"([^\"]*)\"");
    private static final Pattern SELECTED_OPTION =
            Pattern.compile("<option value=\"([^\"]*)\"[^>]*\\sselected=\"selected\"");
    private static final String[] SEVERITIES = {"critical", "high", "warning", "info"};
    private static final String[] BAD_SEVERITIES = {
            "urgent", "crit", "criticall", "sev1", "<b>high</b>", "high & low", "'info'", "\"warning\"", "warn ing"};
    private static final String[] BAD_TIMES = {
            "yesterday", "2026-13-01T00:00:00Z", "2026-11-23 14:40:00Z", "1700000000", "2026-11-23",
            "<img src=x onerror=alert(1)>", "2026-11-23T14:40:00+25:00", " 2026-11-23T14:40:00Z", "now & then"};
    private static final String[] HTML_PAYLOADS = {
            "<script>alert('x')</script>", "\"><img src=x onerror=alert(1)>", "a & b", "it's \"quoted\"",
            "javascript:alert(1)", "<b>bold</b>", "&amp;&lt;", "caf\u00e9 <\u00e9>"};
    private static final String[] SERVICES = {"checkout-api", "payments", "inventory-svc", "web.frontend"};

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private IncidentRepository incidents;
    @Autowired
    private InvestigationRepository investigations;
    @Autowired
    private ScriptedChatModel chatModel;

    /** Incidents whose investigation this case started, through the form or the JSON post. */
    private final List<String> started = new ArrayList<>();

    /** Waits for the investigations this case started, so they do not pile up or reach later tests. */
    @AfterEach
    void awaitStartedInvestigations() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline && started.stream()
                .map(investigations::find)
                .anyMatch(i -> i.isEmpty() || i.get().status() == InvestigationStatus.PENDING
                        || i.get().status() == InvestigationStatus.RUNNING)) {
            Thread.sleep(10);
        }
    }

    @ParameterizedTest(name = "seed={0}")
    @MethodSource("dev.investigator.testsupport.PropertySeeds#seeds")
    void formSubmissionMatchesAlertIntakeAndPreservesRejectedInput(long seed) {
        PropertySeeds.check(seed, random -> {
            chatModel.reset();
            String token = "-" + Long.toHexString(seed);
            ScenarioPreset preset = ScenarioPreset.ALL.get(random.nextInt(ScenarioPreset.ALL.size()));
            Map<String, String> fields = new LinkedHashMap<>();
            putIfPresent(fields, "alert", text(random, "HighErrorRate", token));
            putIfPresent(fields, "service", text(random, SERVICES[random.nextInt(SERVICES.length)], ""));
            putIfPresent(fields, "severity", severity(random));
            putIfPresent(fields, "detectedAt", detectedAt(random));

            // The form, as a browser without JavaScript submits it.
            MockHttpServletRequestBuilder form = post("/incidents")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("scenario", preset.key());
            fields.forEach(form::param);
            MockHttpServletResponse formResponse = mvc.perform(form).andReturn().getResponse();

            // The same alert through the JSON Alert_Intake.
            Map<String, Object> json = new LinkedHashMap<>(fields);
            Map<String, String> labels = new LinkedHashMap<>();
            labels.put(ScenarioKind.LABEL, preset.key());
            labels.put("environment", "production");
            json.put("labels", labels);
            MockHttpServletResponse jsonResponse = mvc.perform(post("/api/v1/alerts")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsBytes(json)))
                    .andReturn().getResponse();
            JsonNode jsonBody = objectMapper.readTree(jsonResponse.getContentAsString(StandardCharsets.UTF_8));

            assertThat(jsonResponse.getStatus()).as("JSON intake status for %s", fields).isIn(202, 400);
            boolean accepted = jsonResponse.getStatus() == 202;
            if (accepted) {
                started.add(jsonBody.get("incidentId").asText());
            }

            if (accepted) {
                assertThat(formResponse.getStatus()).as("form status for accepted %s", fields).isEqualTo(302);
                String location = formResponse.getRedirectedUrl();
                assertThat(location).as("redirect for %s", fields).isNotNull();
                Matcher redirect = REDIRECT.matcher(location);
                assertThat(redirect.matches()).as("redirect %s", location).isTrue();
                String id = redirect.group(1);
                started.add(id);
                Incident incident = incidents.find(id).orElseThrow(() -> new AssertionError("no incident " + id));
                assertThat(incident.title()).isEqualTo(fields.get("alert") + " on " + fields.get("service"));
                assertThat(incident.detectedAt()).isEqualTo(OffsetDateTime.parse(fields.get("detectedAt")).toInstant());
                assertThat(incident.labels()).containsEntry(ScenarioKind.LABEL, preset.key())
                        .containsEntry("environment", "production");
            } else {
                assertThat(formResponse.getStatus()).as("form status for rejected %s", fields).isEqualTo(400);
                assertThat(formResponse.getRedirectedUrl()).isNull();
                String page = formResponse.getContentAsString(StandardCharsets.UTF_8);
                assertThat(page).contains("id=\"form-errors\"", "class=\"field-error\"");

                TreeSet<String> jsonFields = new TreeSet<>();
                jsonBody.get("errors").forEach(e -> jsonFields.add(e.get("field").asText()));
                assertThat(errorFields(page)).as("rejected fields for %s", fields).isEqualTo(jsonFields);

                fields.forEach((name, value) -> {
                    String raw = inputValue(page, name);
                    assertThat(raw).as("escaped %s", name).doesNotContain("<", ">");
                    assertThat(HtmlUtils.htmlUnescape(raw)).as("re-rendered %s", name).isEqualTo(value);
                    if (value.contains("<")) {
                        assertThat(page).as("raw %s markup", name).doesNotContain(value);
                    }
                });
                assertThat(selectedScenarios(page)).containsExactly(preset.key());
            }
        });
    }

    private static void putIfPresent(Map<String, String> fields, String name, String value) {
        if (value != null) {
            fields.put(name, value);
        }
    }

    /**
     * Mostly a valid name ending in {@code token} (plain, long or with HTML metacharacters), otherwise empty,
     * whitespace or absent ({@code null}).
     */
    private static String text(SplittableRandom random, String base, String token) {
        return switch (random.nextInt(10)) {
            case 0 -> null;
            case 1 -> random.nextBoolean() ? "" : " ".repeat(1 + random.nextInt(3));
            case 2 -> base.repeat(30 + random.nextInt(170)) + token;
            case 3, 4 -> HTML_PAYLOADS[random.nextInt(HTML_PAYLOADS.length)] + base + token;
            default -> base + token;
        };
    }

    /** A known severity in random case, sometimes padded; otherwise unknown, blank or absent. */
    private static String severity(SplittableRandom random) {
        int pick = random.nextInt(10);
        if (pick == 0) {
            return null;
        }
        if (pick == 1) {
            return random.nextBoolean() ? "" : "  ";
        }
        if (pick == 2) {
            return BAD_SEVERITIES[random.nextInt(BAD_SEVERITIES.length)];
        }
        StringBuilder out = new StringBuilder();
        for (char c : SEVERITIES[random.nextInt(SEVERITIES.length)].toCharArray()) {
            out.append(random.nextBoolean() ? Character.toUpperCase(c) : c);
        }
        return random.nextInt(5) == 0 ? " " + out + " " : out.toString();
    }

    /** An ISO-8601 time with a random offset (Z or ±hh:mm); otherwise offset-less, garbage, blank or absent. */
    private static String detectedAt(SplittableRandom random) {
        OffsetDateTime time = Instant.parse("2026-01-01T00:00:00Z")
                .plusSeconds(random.nextLong(366L * 24 * 3600))
                .plusMillis(random.nextBoolean() ? 0 : random.nextInt(1000))
                .atOffset(ZoneOffset.ofTotalSeconds(15 * 60 * (random.nextInt(105) - 48)));
        return switch (random.nextInt(10)) {
            case 0 -> null;
            case 1 -> random.nextBoolean() ? "" : "   ";
            case 2 -> time.toLocalDateTime().truncatedTo(ChronoUnit.SECONDS).toString();
            case 3 -> BAD_TIMES[random.nextInt(BAD_TIMES.length)];
            case 4 -> time.withOffsetSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            default -> time.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        };
    }

    /** The field names listed in the form's error summary. */
    private static TreeSet<String> errorFields(String page) {
        TreeSet<String> names = new TreeSet<>();
        Matcher m = ERROR_FIELD.matcher(page);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    /** The raw (still escaped) {@code value} attribute of the input with this id. */
    private static String inputValue(String page, String id) {
        Matcher input = Pattern.compile("<input\\b[^>]*\\bid=\"" + Pattern.quote(id) + "\"[^>]*>").matcher(page);
        assertThat(input.find()).as("input %s", id).isTrue();
        Matcher value = VALUE.matcher(input.group());
        assertThat(value.find()).as("value of input %s in %s", id, input.group()).isTrue();
        return value.group(1);
    }

    private static List<String> selectedScenarios(String page) {
        Matcher selected = SELECTED_OPTION.matcher(page);
        List<String> keys = new ArrayList<>();
        while (selected.find()) {
            keys.add(selected.group(1));
        }
        return keys;
    }
}
