package dev.investigator.web;

import dev.investigator.api.MockAlertRequest;
import dev.investigator.integration.mock.ScenarioKind;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Form-backed bean of the New_Alert_Form (web UI Req 3.2 to 3.6). Holds the values exactly as entered, so a
 * rejected submission re-renders with them (Req 3.6). {@link #toRequest()} builds the {@link MockAlertRequest}
 * that {@code POST /api/v1/alerts} would receive, so the same validation applies (Req 3.4).
 */
public class AlertForm {

    private String alert;
    private String service;
    private String severity;
    private String detectedAt;
    private String scenario;

    public String getAlert() {
        return alert;
    }

    public void setAlert(String alert) {
        this.alert = alert;
    }

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getDetectedAt() {
        return detectedAt;
    }

    public void setDetectedAt(String detectedAt) {
        this.detectedAt = detectedAt;
    }

    public String getScenario() {
        return scenario;
    }

    public void setScenario(String scenario) {
        this.scenario = scenario;
    }

    /**
     * The alert as a Mock_Alert: the four fields unchanged, labels {@code scenario=<preset key>} plus the
     * preset's extra labels, no dashboard links. The preset is {@link ScenarioPreset#byKey} of {@link #scenario}.
     */
    public MockAlertRequest toRequest() {
        ScenarioPreset preset = ScenarioPreset.byKey(scenario);
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(ScenarioKind.LABEL, preset.key());
        preset.extraLabels().forEach(labels::putIfAbsent);
        return new MockAlertRequest(alert, service, severity, detectedAt, labels, null);
    }
}
