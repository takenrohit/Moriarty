package dev.investigator.api;

import dev.investigator.domain.Incident;
import dev.investigator.domain.IncidentStatus;
import dev.investigator.domain.Investigation;
import dev.investigator.domain.InvestigationReport;
import dev.investigator.domain.InvestigationStatus;
import dev.investigator.domain.Severity;
import dev.investigator.report.ReportRenderer;
import dev.investigator.store.IncidentRepository;
import dev.investigator.store.InvestigationRepository;
import dev.investigator.store.ReportRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Incident_API: the Incident with its Investigation status, and its report as JSON or Markdown
 * (Req 6.7, 6.8, 17.1 to 17.3).
 *
 * <p>Path IDs must match {@value #INCIDENT_ID_REGEX}; any other ID, and any unknown ID, gives 404
 * without a lookup. A report request before the report is stored gives 202 with the Investigation
 * status. JSON times are UTC ISO-8601 strings (Req 17.5).
 *
 * <p>{@code report.md} sets its content type on the response instead of declaring {@code produces},
 * so a 404 for it can still be written as the JSON error body.
 */
@RestController
@RequestMapping("/api/v1/incidents")
public class IncidentController {

    static final String INCIDENT_ID_REGEX = "^inc-[0-9a-f]{12}$";
    private static final Pattern INCIDENT_ID = Pattern.compile(INCIDENT_ID_REGEX);

    /** {@code text/markdown;charset=UTF-8}; the charset keeps the em dash in the heading intact. */
    public static final MediaType TEXT_MARKDOWN = new MediaType("text", "markdown", StandardCharsets.UTF_8);

    private final IncidentRepository incidents;
    private final InvestigationRepository investigations;
    private final ReportRepository reports;
    private final ReportRenderer renderer;

    public IncidentController(IncidentRepository incidents, InvestigationRepository investigations,
            ReportRepository reports, ReportRenderer renderer) {
        this.incidents = incidents;
        this.investigations = investigations;
        this.reports = reports;
        this.renderer = renderer;
    }

    /** True if {@code id} has the Incident_ID format {@code inc-} plus 12 lowercase hex digits. */
    public static boolean isIncidentId(String id) {
        return id != null && INCIDENT_ID.matcher(id).matches();
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public IncidentView get(@PathVariable String id) {
        Incident incident = incident(id);
        return IncidentView.of(incident, investigationStatus(id));
    }

    @GetMapping(value = "/{id}/report", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> report(@PathVariable String id) {
        incident(id);
        Optional<InvestigationReport> report = reports.find(id);
        if (report.isPresent()) {
            return ResponseEntity.ok(report.get());
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new ReportPending(id, investigationStatus(id)));
    }

    @GetMapping("/{id}/report.md")
    public ResponseEntity<String> reportMarkdown(@PathVariable String id) {
        Incident incident = incident(id);
        Optional<InvestigationReport> report = reports.find(id);
        if (report.isPresent()) {
            return ResponseEntity.ok().contentType(TEXT_MARKDOWN).body(renderer.markdown(incident, report.get()));
        }
        String body = "Report for " + id + " is not ready; investigation status: " + investigationStatus(id) + "\n";
        return ResponseEntity.status(HttpStatus.ACCEPTED).contentType(TEXT_MARKDOWN).body(body);
    }

    private Incident incident(String id) {
        if (!isIncidentId(id)) {
            throw new IncidentNotFoundException(id);
        }
        return incidents.find(id).orElseThrow(() -> new IncidentNotFoundException(id));
    }

    /**
     * The Investigation status; {@code PENDING} in the short gap between storing the Incident and
     * creating its Investigation.
     */
    private InvestigationStatus investigationStatus(String id) {
        return investigations.find(id).map(Investigation::status).orElse(InvestigationStatus.PENDING);
    }

    /** {@code GET /api/v1/incidents/{id}}: the Incident's fields plus its Investigation status (Req 6.7). */
    public record IncidentView(String id, String title, Severity severity, String affectedService,
            Instant detectedAt, String alertName, Map<String, String> labels, List<String> dashboardLinks,
            String fingerprint, IncidentStatus status, InvestigationStatus investigationStatus, String reportUrl) {

        static IncidentView of(Incident incident, InvestigationStatus investigationStatus) {
            return new IncidentView(incident.id(), incident.title(), incident.severity(), incident.affectedService(),
                    incident.detectedAt(), incident.alertName(), incident.labels(), incident.dashboardLinks(),
                    incident.fingerprint(), incident.status(), investigationStatus,
                    "/api/v1/incidents/" + incident.id() + "/report");
        }
    }

    /** 202 body while the report is not stored yet (Req 17.2). */
    public record ReportPending(String incidentId, InvestigationStatus status) {
    }
}
