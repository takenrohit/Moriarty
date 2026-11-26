package dev.investigator.api;

import dev.investigator.feedback.Feedback;
import dev.investigator.feedback.FeedbackService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Feedback on reports (Req 8.1 to 8.4): {@code POST /api/v1/reports/{incidentId}/feedback} stores one
 * submission and returns 201; an invalid body gives 400 and stores nothing; a malformed ID or an Incident
 * without a stored report gives 404. {@code GET} lists the submissions, oldest first.
 */
@RestController
@RequestMapping("/api/v1/reports/{incidentId}/feedback")
public class FeedbackController {

    private final FeedbackService service;

    public FeedbackController(FeedbackService service) {
        this.service = service;
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public FeedbackView submit(@PathVariable String incidentId, @Valid @RequestBody FeedbackRequest request) {
        requireIncidentId(incidentId);
        Feedback.Rating rating = Feedback.Rating.valueOf(request.rating().toUpperCase(Locale.ROOT));
        return service.submit(incidentId, rating, request.note(), request.submittedBy())
                .map(FeedbackView::of)
                .orElseThrow(() -> new ReportNotFoundException(incidentId));
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<FeedbackView> list(@PathVariable String incidentId) {
        requireIncidentId(incidentId);
        return service.list(incidentId).stream().map(FeedbackView::of).toList();
    }

    private static void requireIncidentId(String incidentId) {
        if (!IncidentController.isIncidentId(incidentId)) {
            throw new ReportNotFoundException(incidentId);
        }
    }

    /** Request body; {@code rating} is {@code helpful} or {@code unhelpful}, any case. */
    public record FeedbackRequest(
            @NotNull @Pattern(regexp = "(?i)helpful|unhelpful", message = "must be helpful or unhelpful") String rating,
            @Size(max = FeedbackService.MAX_NOTE) String note,
            @Size(max = FeedbackService.MAX_SUBMITTER) String submittedBy) {
    }

    /** Stored feedback; {@code rating} is lowercase. */
    public record FeedbackView(long id, String incidentId, String rating, String note, String submittedBy,
            Instant createdAt) {

        static FeedbackView of(Feedback feedback) {
            return new FeedbackView(feedback.id(), feedback.incidentId(),
                    feedback.rating().name().toLowerCase(Locale.ROOT), feedback.note(), feedback.submittedBy(),
                    feedback.createdAt());
        }
    }
}
