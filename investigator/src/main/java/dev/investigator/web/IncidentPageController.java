package dev.investigator.web;

import dev.investigator.api.IncidentController;
import dev.investigator.domain.Incident;
import dev.investigator.domain.Investigation;
import dev.investigator.domain.InvestigationStatus;
import dev.investigator.report.DisplayTimeFormatter;
import dev.investigator.report.ReportRenderer;
import dev.investigator.store.IncidentRepository;
import dev.investigator.store.InvestigationRepository;
import dev.investigator.store.ReportRepository;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.ModelAndView;

/**
 * Report_Page: lists Incidents newest first at {@code /incidents} and shows one Incident with its
 * report at {@code /incidents/{id}} (Req 17.4). Times are in the Display_Timezone; the templates use
 * only {@code th:text}, so all report and evidence text is HTML-escaped (Req 17.8).
 *
 * <p>Unknown or malformed IDs give 404 through {@link ResponseStatusException}, rendered here as the
 * dark {@code error.html} page rather than the API's JSON error body (Req 6.8; web UI Req 4.7).
 */
@Controller
public class IncidentPageController {

    private final IncidentRepository incidents;
    private final InvestigationRepository investigations;
    private final ReportRepository reports;
    private final ReportRenderer renderer;

    public IncidentPageController(IncidentRepository incidents, InvestigationRepository investigations,
            ReportRepository reports, ReportRenderer renderer) {
        this.incidents = incidents;
        this.investigations = investigations;
        this.reports = reports;
        this.renderer = renderer;
    }

    @GetMapping("/incidents")
    public String list(Model model) {
        List<IncidentRow> rows = incidents.findAllNewestFirst().stream().map(this::row).toList();
        model.addAttribute("incidents", rows);
        model.addAttribute("zone", renderer.times().zone().getId());
        return "incidents";
    }

    @GetMapping("/incidents/{id}")
    public String detail(@PathVariable String id, Model model) {
        if (!IncidentController.isIncidentId(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Incident incident = incidents.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("incident", row(incident));
        model.addAttribute("report", reports.find(id).map(r -> renderer.view(incident, r)).orElse(null));
        model.addAttribute("zone", renderer.times().zone().getId());
        return "incident";
    }

    /**
     * Renders {@code error.html} for page errors such as a malformed or unknown ID (Req 4.7), with only
     * the status code and its reason phrase in the model, so no exception text reaches the page.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ModelAndView pageError(ResponseStatusException e) {
        HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        ModelAndView view = new ModelAndView("error", status);
        view.addObject("status", status.value());
        view.addObject("error", status.getReasonPhrase());
        return view;
    }

    private IncidentRow row(Incident incident) {
        DisplayTimeFormatter times = renderer.times();
        InvestigationStatus status = investigations.find(incident.id()).map(Investigation::status)
                .orElse(InvestigationStatus.PENDING);
        String severity = "";
        if (incident.severity() != null) {
            String lower = incident.severity().name().toLowerCase(Locale.ROOT);
            severity = Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
        }
        return new IncidentRow(incident.id(), incident.title(), incident.affectedService(), severity,
                incident.detectedAt() == null ? "" : times.dateTime(incident.detectedAt()),
                incident.status() == null ? "" : incident.status().name(), status.name());
    }

    /** One Incident as shown on the pages; {@code detected} is in the Display_Timezone. */
    public record IncidentRow(String id, String title, String service, String severity, String detected,
            String incidentStatus, String investigationStatus) {
    }
}
