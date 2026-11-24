package dev.investigator.report;

import dev.investigator.domain.DraftAction;
import dev.investigator.domain.DraftHypothesis;
import dev.investigator.domain.DraftTimelineEntry;
import dev.investigator.domain.EvidenceRef;
import dev.investigator.domain.Hypothesis;
import dev.investigator.domain.IncidentTimeline;
import dev.investigator.domain.InvestigationReport;
import dev.investigator.domain.InvestigationStatus;
import dev.investigator.domain.RecommendedAction;
import dev.investigator.domain.ReportDraft;
import dev.investigator.domain.TimelineEntry;
import dev.investigator.investigation.InvestigationContext;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Builds the {@link InvestigationReport} from a parsed {@link ReportDraft} (design "Report pipeline").
 *
 * <p>The Incident_ID comes from the investigation context, timeline times are converted to
 * {@link Instant}, actions are sorted by {@code order} (stable for equal orders), the extra open
 * questions are appended to the draft's, and the evidence references and status are attached.
 * Task 17.1 adds the {@code ActionPolicy}, which overwrites {@code requiresApproval}; until then the
 * draft's value is kept.
 */
@Component
public final class ReportAssembler {

    /**
     * @param status {@code COMPLETED} or {@code INCONCLUSIVE}
     * @param extraOpenQuestions appended after the draft's open questions, for example the budget note
     * @throws IllegalArgumentException if a timeline time is not an ISO-8601 instant, or the status is
     *     not {@code COMPLETED} or {@code INCONCLUSIVE}
     */
    public InvestigationReport assemble(ReportDraft draft, InvestigationContext ctx, InvestigationStatus status,
            List<String> extraOpenQuestions, List<EvidenceRef> evidence, Instant generatedAt) {
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(generatedAt, "generatedAt");
        if (status != InvestigationStatus.COMPLETED && status != InvestigationStatus.INCONCLUSIVE) {
            throw new IllegalArgumentException("a report built from a draft is COMPLETED or INCONCLUSIVE, not " + status);
        }

        List<TimelineEntry> timeline = new ArrayList<>();
        for (int i = 0; i < draft.timeline().size(); i++) {
            DraftTimelineEntry entry = draft.timeline().get(i);
            timeline.add(new TimelineEntry(instant(entry.at(), i), entry.description(), entry.evidenceIds()));
        }

        List<Hypothesis> hypotheses = draft.hypotheses().stream()
                .map(ReportAssembler::hypothesis)
                .toList();

        List<RecommendedAction> actions = draft.actions().stream()
                .sorted(Comparator.comparingInt(DraftAction::order))
                .map(a -> new RecommendedAction(a.order(), a.type(), a.description(), a.requiresApproval(),
                        a.evidenceIds()))
                .toList();

        List<String> openQuestions = new ArrayList<>(draft.openQuestions());
        if (extraOpenQuestions != null) {
            extraOpenQuestions.stream().filter(Objects::nonNull).forEach(openQuestions::add);
        }

        return new InvestigationReport(ctx.incidentId(), status, draft.summary(), new IncidentTimeline(timeline),
                hypotheses, actions, openQuestions, evidence, generatedAt);
    }

    private static Hypothesis hypothesis(DraftHypothesis h) {
        return new Hypothesis(h.statement(), h.confidence(), h.supportingEvidenceIds(), h.contradictingEvidenceIds(),
                h.establishedCause());
    }

    private static Instant instant(String at, int index) {
        String path = "$.timeline[" + index + "].at";
        if (at == null) {
            throw new IllegalArgumentException(path + ": is required");
        }
        try {
            return Instant.parse(at);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(path + ": is not an ISO-8601 instant", e);
        }
    }
}
