package dev.investigator.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Builds the Slack Block Kit message of a report from its {@link ReportView}, so times are the same
 * Display_Timezone strings, with zone abbreviation, as in the Markdown rendering (Req 13.2, 13.5).
 *
 * <p>Layout: a header (title and status), a fields section (incident, service, severity, status,
 * detection time), then one section each for the summary, hypotheses, timeline, recommended actions
 * (with their approval markers), open questions and evidence, and a "View report" link button.
 * Every Evidence_ID the Markdown cites (hypotheses, timeline, actions, evidence list) is written as
 * {@code `id`} (Req 13.7).
 *
 * <p>Slack limits: at most {@value #MAX_BLOCKS} blocks, {@value #MAX_SECTION_TEXT} characters per section
 * text, {@value #MAX_FIELD_TEXT} per field and {@value #MAX_HEADER_TEXT} for the header. A section that
 * is too long continues in further sections, splitting between lines. Free text (statements,
 * descriptions, questions, headlines) is cut with "…" to {@value #FIRST_ITEM_LIMIT} characters, and to
 * shorter limits if the message would still exceed the block limit; Evidence_IDs are never cut. Only
 * if the shortest limit still needs more blocks is the tail dropped, with a notice pointing to the
 * full report. All mrkdwn text escapes {@code &}, {@code <} and {@code >}.
 */
@Component
public class SlackBlockKitRenderer {

    static final int MAX_BLOCKS = 50;
    static final int MAX_SECTION_TEXT = 3000;
    static final int MAX_FIELD_TEXT = 2000;
    static final int MAX_HEADER_TEXT = 150;
    static final int MAX_FALLBACK_TEXT = 3000;
    static final int FIRST_ITEM_LIMIT = 500;
    static final String ELLIPSIS = "…";
    static final String TRUNCATED_NOTICE = "_Report truncated for Slack; open the full report for the rest._";

    private static final int[] ITEM_LIMITS = {FIRST_ITEM_LIMIT, 150, 40};
    private static final int SUMMARY_LIMIT = 2800;

    /**
     * One Slack message.
     *
     * @param text plain fallback text for notifications
     * @param blocks Block Kit blocks, serialized as JSON by the HTTP client
     */
    public record SlackMessage(String text, List<Map<String, Object>> blocks) {

        public SlackMessage {
            blocks = List.copyOf(blocks);
        }
    }

    /**
     * Renders the message.
     *
     * @param reportUrl absolute URL of the report page for the "View report" button, or {@code null} for none
     */
    public SlackMessage render(ReportView view, String reportUrl) {
        List<Map<String, Object>> blocks = List.of();
        for (int limit : ITEM_LIMITS) {
            blocks = build(view, reportUrl, limit);
            if (blocks.size() <= MAX_BLOCKS) {
                return new SlackMessage(fallbackText(view), blocks);
            }
        }
        List<Map<String, Object>> kept = new ArrayList<>(blocks.subList(0, MAX_BLOCKS - (reportUrl == null ? 1 : 2)));
        kept.add(section(TRUNCATED_NOTICE));
        if (reportUrl != null) {
            kept.add(button(reportUrl));
        }
        return new SlackMessage(fallbackText(view), kept);
    }

    /** Escapes the three characters Slack mrkdwn requires escaped. */
    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** {@code text} cut to at most {@code max} characters, ending in "…" when cut. */
    static String cut(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int end = max - ELLIPSIS.length();
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, Math.max(end, 0)) + ELLIPSIS;
    }

    private List<Map<String, Object>> build(ReportView v, String reportUrl, int limit) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(header(v));
        blocks.add(fields(v));
        String summary = v.summary().isBlank() ? ReportRenderer.NONE : cut(v.summary(), SUMMARY_LIMIT);
        sections(blocks, "What happened", List.of(escape(summary)));
        sections(blocks, "Hypotheses", hypotheses(v, limit));
        sections(blocks, v.timelineHeading(), timeline(v, limit));
        sections(blocks, "Recommended next steps", actions(v, limit));
        sections(blocks, "Open questions",
                v.openQuestions().stream().map(q -> "• " + escape(cut(q, limit))).toList());
        sections(blocks, "Evidence", evidence(v, limit));
        if (reportUrl != null) {
            blocks.add(button(reportUrl));
        }
        return blocks;
    }

    private static List<String> hypotheses(ReportView v, int limit) {
        List<String> lines = new ArrayList<>();
        for (ReportView.HypothesisView h : v.hypotheses()) {
            lines.add(h.number() + ". " + escape(cut(h.statement(), limit)) + " (confidence: " + escape(h.confidence())
                    + ", " + (h.establishedCause() ? "established cause" : "correlated, not established") + ")"
                    + ids(h.supportingEvidenceIds()));
            if (!h.contradictingEvidenceIds().isEmpty()) {
                lines.add("    Contradicting:" + ids(h.contradictingEvidenceIds()));
            }
            h.correlationStatements().forEach(s -> lines.add("    _" + escape(cut(s, limit)) + "_"));
        }
        return lines;
    }

    private static List<String> timeline(ReportView v, int limit) {
        return v.timeline().stream()
                .map(e -> "`" + escape(e.time()) + "` " + escape(cut(e.description(), limit)) + ids(e.evidenceIds()))
                .toList();
    }

    private static List<String> actions(ReportView v, int limit) {
        return v.actions().stream()
                .map(a -> {
                    String marker = escape(a.marker());
                    return a.order() + ". " + escape(cut(a.description(), limit)) + ids(a.evidenceIds()) + " "
                            + (a.requiresApproval() ? ":warning: *" + marker + "*" : marker);
                })
                .toList();
    }

    private static List<String> evidence(ReportView v, int limit) {
        return v.evidence().stream()
                .map(e -> "• `" + escape(e.id()) + "` " + escape(e.type()) + " " + escape(cut(e.source(), limit))
                        + " " + escape(e.observedAt()) + " — " + escape(cut(e.headline(), limit)))
                .toList();
    }

    private static String ids(List<String> ids) {
        StringBuilder sb = new StringBuilder();
        ids.forEach(id -> sb.append(" `").append(escape(id)).append('`'));
        return sb.toString();
    }

    /** Appends a titled section, continued in further sections when longer than the section limit. */
    private static void sections(List<Map<String, Object>> blocks, String title, List<String> lines) {
        List<String> pieces = new ArrayList<>();
        (lines.isEmpty() ? List.of(ReportRenderer.NONE) : lines).forEach(line -> split(line, pieces));
        StringBuilder text = new StringBuilder("*").append(escape(cut(title, 200))).append('*');
        for (String piece : pieces) {
            if (text.length() + 1 + piece.length() > MAX_SECTION_TEXT) {
                blocks.add(section(text.toString()));
                text = new StringBuilder(piece);
            } else {
                text.append('\n').append(piece);
            }
        }
        blocks.add(section(text.toString()));
    }

    /** Splits a line longer than the section limit at spaces (and, failing that, hard) into pieces. */
    private static void split(String line, List<String> out) {
        if (line.length() <= MAX_SECTION_TEXT) {
            out.add(line);
            return;
        }
        StringBuilder piece = new StringBuilder();
        for (String token : line.split(" ")) {
            while (token.length() > MAX_SECTION_TEXT) {
                if (!piece.isEmpty()) {
                    out.add(piece.toString());
                    piece.setLength(0);
                }
                out.add(token.substring(0, MAX_SECTION_TEXT));
                token = token.substring(MAX_SECTION_TEXT);
            }
            if (piece.length() + 1 + token.length() > MAX_SECTION_TEXT) {
                out.add(piece.toString());
                piece.setLength(0);
            }
            if (!piece.isEmpty()) {
                piece.append(' ');
            }
            piece.append(token);
        }
        if (!piece.isEmpty()) {
            out.add(piece.toString());
        }
    }

    private static Map<String, Object> header(ReportView v) {
        String title = v.title().isBlank() ? "Incident " + v.incidentId() : v.title();
        String status = v.status().isBlank() ? "" : " — " + v.status();
        String text = title.length() + status.length() <= MAX_HEADER_TEXT
                ? title + status
                : cut(title, Math.max(MAX_HEADER_TEXT - status.length(), 10)) + status;
        return Map.of("type", "header", "text", plainText(cut(text, MAX_HEADER_TEXT)));
    }

    private static Map<String, Object> fields(ReportView v) {
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(field("Incident", "`" + v.incidentId() + "`"));
        fields.add(field("Service", v.service()));
        fields.add(field("Severity", v.severity()));
        fields.add(field("Status", v.status()));
        fields.add(field("Detected", v.detected()));
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "section");
        block.put("fields", fields);
        return block;
    }

    private static Map<String, Object> field(String name, String value) {
        String shown = value == null || value.isBlank() ? "-" : value;
        // Cut before escaping so an entity is never split; 300 escaped characters stay below the field limit.
        return mrkdwn("*" + name + "*\n" + escape(cut(shown, 300)));
    }

    private static Map<String, Object> section(String mrkdwnText) {
        return Map.of("type", "section", "text", mrkdwn(mrkdwnText));
    }

    private static Map<String, Object> button(String url) {
        Map<String, Object> button = new LinkedHashMap<>();
        button.put("type", "button");
        button.put("text", plainText("View report"));
        button.put("url", url);
        button.put("action_id", "view_report");
        return Map.of("type", "actions", "elements", List.of(button));
    }

    private static Map<String, Object> mrkdwn(String text) {
        return Map.of("type", "mrkdwn", "text", text);
    }

    private static Map<String, Object> plainText(String text) {
        return Map.of("type", "plain_text", "text", text, "emoji", true);
    }

    private static String fallbackText(ReportView v) {
        String title = v.title().isBlank() ? v.incidentId() : v.title();
        return escape(cut("Incident report: " + title + " — " + v.status() + ", " + v.severity() + ", " + v.service(),
                MAX_FALLBACK_TEXT));
    }
}
