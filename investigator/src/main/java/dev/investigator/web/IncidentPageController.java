package dev.investigator.web;

import dev.investigator.alert.AlertIntakeService;
import dev.investigator.alert.AlertValidationException;
import dev.investigator.api.AlertAcceptedResponse;
import dev.investigator.api.IncidentController;
import dev.investigator.api.MockAlertRequest;
import dev.investigator.config.WebhookProperties;
import dev.investigator.domain.Incident;
import dev.investigator.domain.Investigation;
import dev.investigator.domain.InvestigationStatus;
import dev.investigator.ratelimit.InvestigationRateLimitedException;
import dev.investigator.report.DisplayTimeFormatter;
import dev.investigator.report.ReportRenderer;
import dev.investigator.store.IncidentRepository;
import dev.investigator.store.InvestigationRepository;
import dev.investigator.store.ReportRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.ModelAndView;

/**
 * Report_Page: lists Incidents newest first at {@code /incidents} and shows one Incident with its
 * report at {@code /incidents/{id}} (Req 17.4). Times are in the Display_Timezone; the templates use
 * only {@code th:text}, so all report and evidence text is HTML-escaped (Req 17.8).
 *
 * <p>Unknown or malformed IDs give 404 through {@link ResponseStatusException}, rendered here as the
 * dark {@code error.html} page rather than the API's JSON error body (Req 6.8; web UI Req 4.7).
 *
 * <p>{@code GET /incidents/new} and {@code POST /incidents} serve the New_Alert_Form, which creates alerts
 * through the same {@link AlertIntakeService} as {@code POST /api/v1/alerts} (web UI Req 3). These paths are
 * outside the webhook authentication filter, so the form is disabled whenever a Webhook_Secret is set.
 */
@Controller
public class IncidentPageController {

    static final String FORM_DISABLED_MESSAGE =
            "Browser alert creation is disabled because webhook authentication is configured.";
    static final String RATE_LIMITED_MESSAGE =
            "The investigation rate limit for this service is reached; no alert was created. Try again later.";

    private final IncidentRepository incidents;
    private final InvestigationRepository investigations;
    private final ReportRepository reports;
    private final ReportRenderer renderer;
    private final AlertIntakeService intake;
    private final WebhookProperties webhook;
    private final Validator validator;
    private final Clock clock;

    public IncidentPageController(IncidentRepository incidents, InvestigationRepository investigations,
            ReportRepository reports, ReportRenderer renderer, AlertIntakeService intake, WebhookProperties webhook,
            Validator validator, Clock clock) {
        this.incidents = incidents;
        this.investigations = investigations;
        this.reports = reports;
        this.renderer = renderer;
        this.intake = intake;
        this.webhook = webhook;
        this.validator = validator;
        this.clock = clock;
    }

    @GetMapping("/incidents")
    public String list(Model model) {
        List<IncidentRow> rows = incidents.findAllNewestFirst().stream().map(this::row).toList();
        model.addAttribute("incidents", rows);
        model.addAttribute("zone", renderer.times().zone().getId());
        return "incidents";
    }

    /**
     * The incident table body alone, for the 10-second list refresh in {@code console.js} (Req 2.4). The
     * literal path is more specific than {@code /incidents/{id}}, so Spring MVC maps it here rather than
     * to {@link #detail}, which would reject {@code fragment} as a malformed ID.
     */
    @GetMapping("/incidents/fragment")
    public String listFragment(Model model) {
        model.addAttribute("incidents", incidents.findAllNewestFirst().stream().map(this::row).toList());
        return "fragments/incident-rows :: rows";
    }

    /**
     * New_Alert_Form, prefilled from the {@code ?scenario=} preset (default {@code payment-gateway}) with the
     * current time in the Display_Timezone (Req 3.1 to 3.3). The literal path is more specific than
     * {@code /incidents/{id}}, so Spring MVC maps it here. With a Webhook_Secret configured the page shows
     * only a notice (Req 3.8).
     */
    @GetMapping("/incidents/new")
    public String newAlert(@RequestParam(required = false) String scenario, Model model) {
        ZoneId zone = renderer.times().zone();
        AlertForm form = ScenarioPreset.byKey(scenario).toForm(OffsetDateTime.now(clock.withZone(zone)));
        formModel(model, form, Map.of(), null);
        return "new-alert";
    }

    /**
     * Creates the alert through the Alert_Intake with the validation of {@code POST /api/v1/alerts}
     * (Req 3.4): bean constraints of {@link dev.investigator.api.MockAlertRequest}, then the normalizer.
     * Success redirects to the Report_Page (Req 3.5); a rejection re-renders the form with 400, the
     * messages and the entered values (Req 3.6); a rate limit re-renders with 429. With a Webhook_Secret
     * configured nothing is created and the answer is 403 (Req 3.8).
     */
    @PostMapping("/incidents")
    public ModelAndView createAlert(@ModelAttribute("form") AlertForm form, Model model) {
        if (webhook.authenticationEnabled()) {
            return formView(model, form, Map.of(), null, HttpStatus.FORBIDDEN);
        }
        MockAlertRequest request = form.toRequest();
        Set<ConstraintViolation<MockAlertRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            Map<String, List<String>> errors = new TreeMap<>();
            violations.forEach(v -> errors.computeIfAbsent(v.getPropertyPath().toString(), k -> new ArrayList<>())
                    .add(v.getMessage()));
            errors.values().forEach(messages -> messages.sort(null));
            return formView(model, form, errors, null, HttpStatus.BAD_REQUEST);
        }
        try {
            AlertAcceptedResponse accepted = intake.accept(request);
            return new ModelAndView("redirect:/incidents/" + accepted.incidentId());
        } catch (AlertValidationException e) {
            Map<String, List<String>> errors = new TreeMap<>();
            e.violations().forEach(v -> errors.computeIfAbsent(v.field(), k -> new ArrayList<>()).add(v.message()));
            return formView(model, form, errors, null, HttpStatus.BAD_REQUEST);
        } catch (InvestigationRateLimitedException e) {
            return formView(model, form, Map.of(), RATE_LIMITED_MESSAGE, HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    private ModelAndView formView(Model model, AlertForm form, Map<String, List<String>> errors, String message,
            HttpStatus status) {
        formModel(model, form, errors, message);
        return new ModelAndView("new-alert", model.asMap(), status);
    }

    private void formModel(Model model, AlertForm form, Map<String, List<String>> errors, String message) {
        boolean enabled = !webhook.authenticationEnabled();
        model.addAttribute("presets", ScenarioPreset.ALL);
        model.addAttribute("form", form);
        model.addAttribute("selectedPreset", ScenarioPreset.byKey(form.getScenario()).key());
        model.addAttribute("formEnabled", enabled);
        model.addAttribute("disabledMessage", enabled ? null : FORM_DISABLED_MESSAGE);
        model.addAttribute("errors", errors);
        model.addAttribute("message", message);
        model.addAttribute("zone", renderer.times().zone().getId());
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
