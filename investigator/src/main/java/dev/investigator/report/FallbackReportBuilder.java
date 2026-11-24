package dev.investigator.report;

import dev.investigator.domain.ActionType;
import dev.investigator.domain.Evidence;
import dev.investigator.domain.EvidenceRef;
import dev.investigator.domain.IncidentTimeline;
import dev.investigator.domain.InvestigationReport;
import dev.investigator.domain.InvestigationStatus;
import dev.investigator.domain.RecommendedAction;
import dev.investigator.domain.TimelineEntry;
import dev.investigator.evidence.Headlines;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Fallback_Report_Builder: the evidence-only report stored when there is no usable draft (Req 14.11).
 *
 * <p>The report has a summary naming the reason, one timeline entry per Evidence ordered by
 * {@code observedAt} then Evidence number, no hypotheses, an {@code INVESTIGATE} and an
 * {@code ESCALATE} action citing every Evidence_ID, and the reason followed by the details as open
 * questions: at most {@value #MAX_OPEN_QUESTIONS} entries of at most
 * {@value #MAX_OPEN_QUESTION_LENGTH} code points each.
 */
@Component
public final class FallbackReportBuilder {

    /** Maximum number of open questions, matching the draft schema's {@code openQuestions} limit. */
    public static final int MAX_OPEN_QUESTIONS = 10;

    /** Maximum length of one open question, in code points. */
    public static final int MAX_OPEN_QUESTION_LENGTH = 300;

    static final String REVIEW_EVIDENCE = "Review the collected evidence manually";
    static final String UNKNOWN_SERVICE = "the affected service";

    /**
     * @param service the Incident's affected service, named in the {@code ESCALATE} action
     * @param status {@code INCONCLUSIVE} or {@code FAILED}
     * @param reason why no validated analysis is available, for example {@code timed out after 3m}
     * @param details further open questions, such as draft validation errors; may be empty
     * @param evidence the Incident's stored Evidence in any order; Evidence of other incidents is ignored
     * @throws IllegalArgumentException if {@code status} is not {@code INCONCLUSIVE} or {@code FAILED}
     */
    public InvestigationReport build(String incidentId, String service, InvestigationStatus status, String reason,
            List<String> details, List<Evidence> evidence, Instant generatedAt) {
        Objects.requireNonNull(incidentId, "incidentId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(generatedAt, "generatedAt");
        if (status != InvestigationStatus.INCONCLUSIVE && status != InvestigationStatus.FAILED) {
            throw new IllegalArgumentException("a fallback report is INCONCLUSIVE or FAILED, not " + status);
        }
        String why = reason == null || reason.isBlank() ? "no reason recorded" : Headlines.normalize(reason);
        why = Headlines.truncate(stripTrailingPeriod(why), MAX_OPEN_QUESTION_LENGTH);

        List<Evidence> own = (evidence == null ? List.<Evidence>of() : evidence).stream()
                .filter(Objects::nonNull)
                .filter(e -> incidentId.equals(e.incidentId()))
                .toList();
        List<String> ids = own.stream().sorted(BY_NUMBER).map(Evidence::id).toList();

        List<TimelineEntry> timeline = own.stream()
                .sorted(Comparator.comparing(Evidence::observedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(BY_NUMBER))
                .map(e -> new TimelineEntry(e.observedAt(), description(e), List.of(e.id())))
                .toList();

        String target = service == null || service.isBlank() ? UNKNOWN_SERVICE : service.trim();
        List<RecommendedAction> actions = List.of(
                new RecommendedAction(1, ActionType.INVESTIGATE, REVIEW_EVIDENCE, false, ids),
                new RecommendedAction(2, ActionType.ESCALATE, "Escalate to the on-call owner of " + target, false,
                        ids));

        String summary = (status == InvestigationStatus.FAILED ? "Investigation failed: " : "Investigation inconclusive: ")
                + why + ". Collected " + own.size() + " evidence " + (own.size() == 1 ? "item" : "items")
                + "; no validated analysis is available.";

        List<EvidenceRef> refs = own.stream().sorted(BY_NUMBER)
                .map(e -> new EvidenceRef(e.id(), e.type(), e.source(), e.observedAt(), e.headline(), e.citation()))
                .toList();

        return new InvestigationReport(incidentId, status, summary, new IncidentTimeline(timeline), List.of(),
                actions, openQuestions(why, details), refs, generatedAt);
    }

    /** The reason, then the details; if they do not fit, the last slot counts the details left out. */
    static List<String> openQuestions(String reason, List<String> details) {
        List<String> cleaned = (details == null ? List.<String>of() : details).stream()
                .filter(d -> d != null && !d.isBlank())
                .map(d -> Headlines.truncate(d.trim(), MAX_OPEN_QUESTION_LENGTH))
                .toList();
        List<String> questions = new ArrayList<>();
        questions.add(reason);
        int room = MAX_OPEN_QUESTIONS - 1;
        if (cleaned.size() <= room) {
            questions.addAll(cleaned);
        } else {
            questions.addAll(cleaned.subList(0, room - 1));
            questions.add((cleaned.size() - (room - 1)) + " more errors not shown");
        }
        return questions;
    }

    private static String description(Evidence e) {
        if (e.headline() != null && !e.headline().isBlank()) {
            return e.headline();
        }
        return e.type() + " evidence from " + e.source();
    }

    private static String stripTrailingPeriod(String text) {
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
    }

    /** Evidence_IDs {@code ev-<incidentId>-<n>} ordered by {@code n}; other IDs after them, by text. */
    static final Comparator<Evidence> BY_NUMBER = Comparator
            .comparingLong((Evidence e) -> number(e.id()))
            .thenComparing(Evidence::id, Comparator.nullsLast(Comparator.naturalOrder()));

    private static long number(String evidenceId) {
        if (evidenceId == null) {
            return Long.MAX_VALUE;
        }
        int dash = evidenceId.lastIndexOf('-');
        try {
            return Long.parseLong(evidenceId.substring(dash + 1));
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }
}
