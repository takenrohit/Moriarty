package dev.investigator.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import dev.investigator.api.MockAlertRequest;
import dev.investigator.domain.Incident;
import dev.investigator.domain.IncidentStatus;
import dev.investigator.domain.Severity;
import dev.investigator.store.InMemoryIncidentRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link AlertNormalizer} (Req 4.5 to 4.8, 5.1, 5.2, 5.4, 5.5, 19.1).
 *
 * <p>The label and link limits (Req 4.8) are bean-validation constraints on {@link MockAlertRequest}
 * that the controller applies with {@code @Valid}; they are checked here with the same validator, and
 * the HTTP 400 mapping for them is covered in {@code AlertControllerTest}.
 */
class AlertNormalizerTest {

    /** SHA-256 of {@code HighErrorRate NUL checkout-api NUL environment=production NUL region=ap-south-1}. */
    private static final String DEMO_FINGERPRINT =
            "5f2a71d6763334709e4e5f7580805513f8fcdb7d27b112e15b9c2d5a6124f77b";

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    private final AlertNormalizer normalizer =
            new AlertNormalizer(new IncidentIdGenerator(), new InMemoryIncidentRepository());

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void demoAlertNormalizesToOpenIncident() {
        Incident incident = normalizer.normalize(demoAlert());

        assertThat(incident.id()).matches(IncidentIdGenerator.ID_PATTERN);
        assertThat(incident.title()).isEqualTo("HighErrorRate on checkout-api");
        assertThat(incident.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(incident.affectedService()).isEqualTo("checkout-api");
        assertThat(incident.detectedAt()).isEqualTo(Instant.parse("2026-11-23T14:40:00Z"));
        assertThat(incident.alertName()).isEqualTo("HighErrorRate");
        assertThat(incident.labels()).isEqualTo(demoLabels());
        assertThat(incident.dashboardLinks()).containsExactly("http://localhost:3000/d/checkout");
        assertThat(incident.status()).isEqualTo(IncidentStatus.OPEN);
    }

    @Test
    void demoAlertHasKnownAnswerFingerprint() {
        assertThat(normalizer.normalize(demoAlert()).fingerprint()).isEqualTo(DEMO_FINGERPRINT);
    }

    @Test
    void offsetTimestampIsConvertedToUtcInstant() {
        Incident incident = normalizer.normalize(alert("critical", "2026-11-23T20:10:00+05:30"));
        assertThat(incident.detectedAt()).isEqualTo(Instant.parse("2026-11-23T14:40:00Z"));
    }

    @ParameterizedTest
    @CsvSource({
        "critical, CRITICAL", "CRITICAL, CRITICAL", "Critical, CRITICAL",
        "high, HIGH", "HIGH, HIGH", "High, HIGH",
        "warning, WARNING", "WARNING, WARNING", "WaRnInG, WARNING",
        "info, INFO", "INFO, INFO", "Info, INFO"
    })
    void severityIsParsedCaseInsensitively(String raw, Severity expected) {
        assertThat(normalizer.normalize(alert(raw, "2026-11-23T14:40:00Z")).severity()).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"urgent", "CRIT-1", "error", "sev1"})
    void unknownSeverityIsRejectedNamingSeverity(String raw) {
        AlertValidationException e = rejected(alert(raw, "2026-11-23T14:40:00Z"));
        assertThat(e.violations()).extracting(AlertValidationException.Violation::field).containsExactly("severity");
        assertThat(e.getMessage()).doesNotContain(raw);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-23T14:40:00", "2026-11-23 14:40:00Z", "2026-11-23", "yesterday"})
    void detectedAtWithoutOffsetOrMalformedIsRejectedNamingDetectedAt(String raw) {
        AlertValidationException e = rejected(alert("critical", raw));
        assertThat(e.violations()).extracting(AlertValidationException.Violation::field)
                .containsExactly("detectedAt");
    }

    @Test
    void missingAndBlankRequiredFieldsAreAllReported() {
        MockAlertRequest request = new MockAlertRequest(null, " ", "", null, null, null);
        assertThat(rejected(request).violations()).extracting(AlertValidationException.Violation::field)
                .containsExactly("alert", "service", "severity", "detectedAt");
    }

    @Test
    void fiftyLabelsPassValidationAndNormalizeAndFiftyOneAreRejected() {
        MockAlertRequest fifty = withLabels(labels(50));
        assertThat(validator.validate(fifty)).isEmpty();
        assertThat(normalizer.normalize(fifty).labels()).hasSize(50);

        assertThat(violatedFields(withLabels(labels(51)))).containsExactly("labels");
    }

    @Test
    void tenLinksPassValidationAndNormalizeAndElevenAreRejected() {
        MockAlertRequest ten = withLinks(links(10));
        assertThat(validator.validate(ten)).isEmpty();
        assertThat(normalizer.normalize(ten).dashboardLinks()).hasSize(10);

        assertThat(violatedFields(withLinks(links(11)))).containsExactly("dashboardLinks");
    }

    private AlertValidationException rejected(MockAlertRequest request) {
        AlertValidationException e =
                catchThrowableOfType(AlertValidationException.class, () -> normalizer.normalize(request));
        assertThat(e).as("normalize must throw AlertValidationException").isNotNull();
        return e;
    }

    private static List<String> violatedFields(MockAlertRequest request) {
        return validator.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .toList();
    }

    private static MockAlertRequest demoAlert() {
        return alert("critical", "2026-11-23T14:40:00Z");
    }

    private static MockAlertRequest alert(String severity, String detectedAt) {
        return new MockAlertRequest("HighErrorRate", "checkout-api", severity, detectedAt, demoLabels(),
                List.of("http://localhost:3000/d/checkout"));
    }

    private static MockAlertRequest withLabels(Map<String, String> labels) {
        return new MockAlertRequest("HighErrorRate", "checkout-api", "critical", "2026-11-23T14:40:00Z",
                labels, List.of());
    }

    private static MockAlertRequest withLinks(List<String> links) {
        return new MockAlertRequest("HighErrorRate", "checkout-api", "critical", "2026-11-23T14:40:00Z",
                Map.of(), links);
    }

    private static Map<String, String> demoLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("environment", "production");
        labels.put("region", "ap-south-1");
        return labels;
    }

    private static Map<String, String> labels(int count) {
        Map<String, String> labels = new LinkedHashMap<>();
        IntStream.range(0, count).forEach(i -> labels.put("k" + i, "v" + i));
        return labels;
    }

    private static List<String> links(int count) {
        return IntStream.range(0, count).mapToObj(i -> "https://grafana.example/d/" + i).toList();
    }
}
