package dev.investigator.report;

import dev.investigator.config.InvestigatorProperties;
import dev.investigator.domain.ActionType;
import dev.investigator.domain.EvidenceRef;
import dev.investigator.domain.EvidenceType;
import dev.investigator.domain.Hypothesis;
import dev.investigator.domain.Incident;
import dev.investigator.domain.InvestigationReport;
import dev.investigator.domain.RecommendedAction;
import dev.investigator.domain.TimelineEntry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Report_Renderer: turns an Investigation_Report into the {@link ReportView} used by the HTML page and
 * into Markdown following the layout of {@code demo-scenario.md} (Req 16.7, 17.5, 17.6).
 *
 * <p>Times are shown in the Display_Timezone. Correlation_Statements are generated here, not taken
 * from the model: for every hypothesis with {@code establishedCause = false}, each cited Evidence_ID
 * (supporting or contradicting) whose Evidence is {@code DEPLOYMENT} or {@code CONFIG} gets the line
 * "Correlated with &lt;headline&gt;; not established as the cause." Each change gets one statement per
 * report, under the first such hypothesis that cites it.
 *
 * <p>Actions of a type that needs approval always show "requires approval", even if the stored flag is
 * false, so a rendering never presents a risky action as safe (FR-41).
 */
@Component
public final class ReportRenderer {

    static final String NONE = "None.";

    private final DisplayTimeFormatter times;

    @Autowired
    public ReportRenderer(InvestigatorProperties properties) {
        this(new DisplayTimeFormatter(properties.displayTimezone()));
    }

    public ReportRenderer(DisplayTimeFormatter times) {
        this.times = Objects.requireNonNull(times, "times");
    }

    public DisplayTimeFormatter times() {
        return times;
    }

    /** The Correlation_Statement for a change with this headline (Req 16.7). */
    public static String correlationStatement(String changeHeadline) {
        return "Correlated with " + changeHeadline + "; not established as the cause.";
    }

    /** True for Change_Evidence types. */
    public static boolean isChange(EvidenceType type) {
        return type == EvidenceType.DEPLOYMENT || type == EvidenceType.CONFIG;
    }

    /** Markdown rendering of the report (Req 17.3, 17.6). */
    public String markdown(Incident incident, InvestigationReport report) {
        return markdown(view(incident, report));
    }

    /** Display model of the report for Thymeleaf; times are in the Display_Timezone. */
    public ReportView view(Incident incident, InvestigationReport report) {
        Objects.requireNonNull(incident, "incident");
        Objects.requireNonNull(report, "report");
        List<TimelineEntry> entries = report.timeline() == null ? List.of() : report.timeline().entries();

        Instant detectedAt = incident.detectedAt();
        List<Instant> shown = new ArrayList<>();
        entries.forEach(e -> shown.add(e.at()));
        report.evidence().forEach(e -> shown.add(e.observedAt()));
        boolean compact = shown.stream().filter(Objects::nonNull).allMatch(i -> sameDayAndZone(i, detectedAt));

        List<ReportView.TimelineEntryView> timeline = entries.stream()
                .map(e -> new ReportView.TimelineEntryView(display(e.at(), compact), text(e.description()),
                        e.evidenceIds()))
                .toList();
        List<ReportView.EvidenceView> evidence = report.evidence().stream()
                .map(e -> new ReportView.EvidenceView(e.id(), name(e.type()), text(e.source()),
                        display(e.observedAt(), compact), text(e.headline()), text(e.citation())))
                .toList();

        return new ReportView(
                report.incidentId(),
                text(incident.title()),
                text(incident.affectedService()),
                incident.severity() == null ? "" : titleCase(incident.severity().name()),
                display(detectedAt, false),
                name(report.status()),
                display(report.generatedAt(), false),
                text(report.summary()),
                hypotheses(report),
                compact ? "Timeline (" + times.zoneAbbreviation(detectedAt) + ")" : "Timeline",
                timeline,
                report.actions().stream().map(ReportRenderer::action).toList(),
                report.openQuestions().stream().map(ReportRenderer::text).toList(),
                evidence);
    }

    /** Markdown rendering of an already built view. */
    public String markdown(ReportView view) {
        StringBuilder md = new StringBuilder();
        md.append("# Incident Investigation — ").append(view.service()).append("\n\n");
        md.append("Severity: ").append(view.severity()).append('\n');
        md.append("Detected: ").append(view.detected()).append('\n');
        md.append("Status: ").append(view.status()).append("\n\n");

        md.append("## What happened\n").append(view.summary()).append("\n\n");

        md.append("## Hypotheses\n");
        if (view.hypotheses().isEmpty()) {
            md.append(NONE).append('\n');
        }
        for (ReportView.HypothesisView h : view.hypotheses()) {
            md.append(h.number()).append(". ").append(h.statement())
                    .append(" (confidence: ").append(h.confidence()).append(')')
                    .append(citations(h.supportingEvidenceIds())).append('\n');
            if (!h.contradictingEvidenceIds().isEmpty()) {
                md.append("   Contradicting:").append(citations(h.contradictingEvidenceIds())).append('\n');
            }
            for (String statement : h.correlationStatements()) {
                md.append("   ").append(statement).append('\n');
            }
        }
        md.append('\n');

        md.append("## ").append(view.timelineHeading()).append('\n');
        if (view.timeline().isEmpty()) {
            md.append(NONE).append('\n');
        }
        for (ReportView.TimelineEntryView e : view.timeline()) {
            md.append(e.time()).append("  ").append(e.description()).append(citations(e.evidenceIds())).append('\n');
        }
        md.append('\n');

        md.append("## Recommended next steps\n");
        if (view.actions().isEmpty()) {
            md.append(NONE).append('\n');
        }
        for (ReportView.ActionView a : view.actions()) {
            md.append(a.order()).append(". ").append(a.description()).append(citations(a.evidenceIds()))
                    .append(' ').append(a.marker()).append('\n');
        }
        md.append('\n');

        md.append("## Open questions\n");
        if (view.openQuestions().isEmpty()) {
            md.append(NONE).append('\n');
        }
        view.openQuestions().forEach(q -> md.append("- ").append(q).append('\n'));
        md.append('\n');

        md.append("## Evidence\n");
        if (view.evidence().isEmpty()) {
            md.append(NONE).append('\n');
        }
        for (ReportView.EvidenceView e : view.evidence()) {
            md.append("- ").append(e.id()).append(' ').append(e.type()).append(' ').append(e.source()).append(' ')
                    .append(e.observedAt()).append(" — ").append(e.headline()).append('\n');
        }
        return md.toString();
    }

    private List<ReportView.HypothesisView> hypotheses(InvestigationReport report) {
        Map<String, EvidenceRef> refs = new LinkedHashMap<>();
        report.evidence().forEach(e -> refs.putIfAbsent(e.id(), e));
        Set<String> stated = new HashSet<>();
        List<ReportView.HypothesisView> views = new ArrayList<>();
        int number = 1;
        for (Hypothesis h : report.hypotheses()) {
            List<String> statements = new ArrayList<>();
            if (!h.establishedCause()) {
                List<String> cited = new ArrayList<>(h.supportingEvidenceIds());
                cited.addAll(h.contradictingEvidenceIds());
                for (String id : cited) {
                    EvidenceRef ref = refs.get(id);
                    if (ref != null && isChange(ref.type()) && stated.add(id)) {
                        String headline = ref.headline() == null || ref.headline().isBlank() ? id : ref.headline();
                        statements.add(correlationStatement(headline));
                    }
                }
            }
            views.add(new ReportView.HypothesisView(number++, text(h.statement()), name(h.confidence()),
                    h.supportingEvidenceIds(), h.contradictingEvidenceIds(), h.establishedCause(), statements));
        }
        return views;
    }

    private static ReportView.ActionView action(RecommendedAction a) {
        String type = a.type() == null ? "unknown" : a.type().name().toLowerCase(Locale.ROOT);
        boolean approval = a.requiresApproval() || needsApproval(a.type());
        String marker = approval ? "[" + type + ", requires approval]" : "[" + type + "]";
        return new ReportView.ActionView(a.order(), type, text(a.description()), approval, marker, a.evidenceIds());
    }

    private static boolean needsApproval(ActionType type) {
        return type != ActionType.INVESTIGATE && type != ActionType.ESCALATE;
    }

    private boolean sameDayAndZone(Instant instant, Instant reference) {
        if (reference == null) {
            return false;
        }
        return times.date(instant).equals(times.date(reference))
                && times.zoneAbbreviation(instant).equals(times.zoneAbbreviation(reference));
    }

    private String display(Instant instant, boolean compact) {
        if (instant == null) {
            return "";
        }
        return compact ? times.time(instant) : times.dateTime(instant);
    }

    private static String citations(List<String> ids) {
        if (ids.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(" ");
        ids.forEach(id -> sb.append('[').append(id).append(']'));
        return sb.toString();
    }

    private static String titleCase(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.isEmpty() ? lower : Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static String name(Enum<?> value) {
        return value == null ? "" : value.name();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
