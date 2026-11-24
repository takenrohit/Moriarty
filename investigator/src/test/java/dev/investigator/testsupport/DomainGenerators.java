package dev.investigator.testsupport;

import dev.investigator.domain.ActionType;
import dev.investigator.domain.Confidence;
import dev.investigator.domain.DraftAction;
import dev.investigator.domain.DraftHypothesis;
import dev.investigator.domain.DraftTimelineEntry;
import dev.investigator.domain.Evidence;
import dev.investigator.domain.EvidenceRef;
import dev.investigator.domain.EvidenceType;
import dev.investigator.domain.Hypothesis;
import dev.investigator.domain.Incident;
import dev.investigator.domain.IncidentStatus;
import dev.investigator.domain.IncidentTimeline;
import dev.investigator.domain.Investigation;
import dev.investigator.domain.InvestigationReport;
import dev.investigator.domain.InvestigationStatus;
import dev.investigator.domain.RecommendedAction;
import dev.investigator.domain.ReportDraft;
import dev.investigator.domain.Severity;
import dev.investigator.domain.TimelineEntry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.function.Function;

/**
 * Seeded generators for the domain records (design "Testing Strategy").
 *
 * <p>Every method takes the property's {@link SplittableRandom} and returns one value, so a seed fixes the
 * input. Collections hold 0 to 5 elements and strings are usually under 40 characters. Boundary values
 * (empty strings and lists, maximum lengths, special characters, {@code Instant} range edges, extreme
 * numbers) are chosen in about 1 case in 10.
 */
public final class DomainGenerators {

    /** Demo scenario alert time; ordinary instants are generated around it. */
    public static final Instant ANCHOR = Instant.parse("2026-11-23T14:40:00Z");

    /** Maximum {@code Evidence.headline} / {@code EvidenceRef.headline} length (design "Data Models"). */
    public static final int HEADLINE_MAX = 200;

    private static final int MAX_ITEMS = 5;
    private static final int SHORT_TEXT = 39;
    private static final String HEX = "0123456789abcdef";
    private static final String ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 -_.:/=,;'\"\\{}[]<>&%éअ€";
    private static final List<String> SPECIAL_STRINGS = List.of(
            "",
            " ",
            "\"quoted\" and \\backslash\\",
            "line1\nline2\r\n\ttab",
            "nul\u0000char and \u001f control",
            "emoji \uD83D\uDD25 surrogate pair",
            "चेकआउट सेवा",
            "<script>alert('x')</script>",
            "{\"looks\":\"like json\"}",
            "null");
    /** Generated instants stay within years 0001 to 9999, the range of four-digit ISO-8601 years. */
    public static final Instant MIN_INSTANT = Instant.parse("0001-01-01T00:00:00Z");
    public static final Instant MAX_INSTANT = Instant.parse("9999-12-31T23:59:59.999999999Z");

    private static final List<Instant> EDGE_INSTANTS = List.of(
            Instant.EPOCH,
            MIN_INSTANT,
            MAX_INSTANT,
            Instant.EPOCH.minusNanos(1),
            ANCHOR.plusNanos(1),
            ANCHOR.plusMillis(999));

    private DomainGenerators() {
    }

    // ---------------------------------------------------------------- records

    public static Incident incident(SplittableRandom r) {
        return new Incident(
                incidentId(r),
                text(r),
                pick(r, Severity.values()),
                serviceName(r),
                instant(r),
                text(r),
                labels(r),
                list(r, DomainGenerators::dashboardLink),
                fingerprint(r),
                pick(r, IncidentStatus.values()));
    }

    public static Evidence evidence(SplittableRandom r) {
        String incidentId = incidentId(r);
        return new Evidence(
                evidenceId(r, incidentId),
                incidentId,
                pick(r, EvidenceType.values()),
                text(r),
                instant(r),
                summaryJson(r),
                headline(r),
                payload(r, 2),
                text(r),
                redactions(r));
    }

    public static EvidenceRef evidenceRef(SplittableRandom r) {
        String incidentId = incidentId(r);
        return new EvidenceRef(
                evidenceId(r, incidentId),
                pick(r, EvidenceType.values()),
                text(r),
                instant(r),
                headline(r),
                text(r));
    }

    public static TimelineEntry timelineEntry(SplittableRandom r) {
        return new TimelineEntry(instant(r), text(r), evidenceIds(r));
    }

    public static IncidentTimeline incidentTimeline(SplittableRandom r) {
        return new IncidentTimeline(list(r, DomainGenerators::timelineEntry));
    }

    public static Hypothesis hypothesis(SplittableRandom r) {
        return new Hypothesis(
                text(r), pick(r, Confidence.values()), evidenceIds(r), evidenceIds(r), r.nextBoolean());
    }

    public static RecommendedAction recommendedAction(SplittableRandom r) {
        return new RecommendedAction(
                order(r), pick(r, ActionType.values()), text(r), r.nextBoolean(), evidenceIds(r));
    }

    public static Investigation investigation(SplittableRandom r) {
        Instant windowStart = instant(r);
        Instant windowEnd = edge(r) ? windowStart : windowStart.plusSeconds(r.nextLong(0, 3_600));
        if (windowEnd.isAfter(MAX_INSTANT)) {
            windowEnd = MAX_INSTANT;
        }
        int toolCallsUsed = edge(r) ? pick(r, 0, Integer.MAX_VALUE) : r.nextInt(0, 21);
        return new Investigation(
                incidentId(r),
                pick(r, InvestigationStatus.values()),
                windowStart,
                windowEnd,
                toolCallsUsed,
                list(r, DomainGenerators::evidence),
                list(r, DomainGenerators::hypothesis),
                incidentTimeline(r),
                list(r, DomainGenerators::recommendedAction));
    }

    public static InvestigationReport investigationReport(SplittableRandom r) {
        return new InvestigationReport(
                incidentId(r),
                pick(r, InvestigationStatus.values()),
                text(r),
                incidentTimeline(r),
                list(r, DomainGenerators::hypothesis),
                list(r, DomainGenerators::recommendedAction),
                list(r, DomainGenerators::text),
                list(r, DomainGenerators::evidenceRef),
                instant(r));
    }

    public static ReportDraft reportDraft(SplittableRandom r) {
        return new ReportDraft(
                incidentId(r),
                text(r),
                list(r, DomainGenerators::draftTimelineEntry),
                list(r, DomainGenerators::draftHypothesis),
                list(r, DomainGenerators::draftAction),
                list(r, DomainGenerators::text));
    }

    public static DraftTimelineEntry draftTimelineEntry(SplittableRandom r) {
        return new DraftTimelineEntry(instant(r).toString(), text(r), evidenceIds(r));
    }

    public static DraftHypothesis draftHypothesis(SplittableRandom r) {
        return new DraftHypothesis(
                text(r), pick(r, Confidence.values()), evidenceIds(r), evidenceIds(r), r.nextBoolean());
    }

    public static DraftAction draftAction(SplittableRandom r) {
        return new DraftAction(order(r), pick(r, ActionType.values()), text(r), r.nextBoolean(), evidenceIds(r));
    }

    // ---------------------------------------------------------------- components

    /** {@code inc-} plus 12 lowercase hex digits. */
    public static String incidentId(SplittableRandom r) {
        return "inc-" + hex(r, 12);
    }

    /** {@code ev-<incidentId>-<n>} with n from 1. */
    public static String evidenceId(SplittableRandom r, String incidentId) {
        int n = edge(r) ? pick(r, 1, Integer.MAX_VALUE) : r.nextInt(1, 30);
        return "ev-" + incidentId + "-" + n;
    }

    public static List<String> evidenceIds(SplittableRandom r) {
        String incidentId = incidentId(r);
        return list(r, g -> evidenceId(g, incidentId));
    }

    /** 64 lowercase hex digits, the shape of a SHA-256 fingerprint. */
    public static String fingerprint(SplittableRandom r) {
        return hex(r, 64);
    }

    public static String serviceName(SplittableRandom r) {
        return edge(r) ? special(r) : pick(r, "checkout-api", "payment-gateway", "inventory-service", "cart-api");
    }

    public static String dashboardLink(SplittableRandom r) {
        return "https://grafana.example.com/d/" + hex(r, 8) + (r.nextBoolean() ? "" : "?from=now-1h&to=now");
    }

    public static Map<String, String> labels(SplittableRandom r) {
        int size = size(r);
        Map<String, String> labels = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            labels.put(text(r), text(r));
        }
        return labels;
    }

    public static Set<String> redactions(SplittableRandom r) {
        return new LinkedHashSet<>(list(r, g -> pick(g, "email", "token", "ip", "card", "not_applied")));
    }

    /** At most {@value #HEADLINE_MAX} characters; exactly the maximum in boundary cases. */
    public static String headline(SplittableRandom r) {
        return edge(r) ? repeat(r, HEADLINE_MAX) : text(r);
    }

    /** A small JSON object, like a capped Tool_Result. */
    public static String summaryJson(SplittableRandom r) {
        return edge(r) ? text(r) : "{\"matches\":" + r.nextInt(0, 500) + ",\"truncated\":" + r.nextBoolean() + "}";
    }

    /**
     * A JSON-shaped map: keys are strings, values are the types Jackson produces when reading untyped JSON
     * ({@code String}, {@code Integer}, {@code Long} outside the int range, finite {@code Double},
     * {@code Boolean}, {@code null}, lists and nested maps). Other types would not compare equal after a
     * round trip because the JSON form does not record them.
     */
    public static Map<String, Object> payload(SplittableRandom r, int depth) {
        int size = size(r);
        Map<String, Object> payload = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            payload.put(text(r), jsonValue(r, depth));
        }
        return payload;
    }

    public static Instant instant(SplittableRandom r) {
        if (edge(r)) {
            return pick(r, EDGE_INSTANTS);
        }
        Instant base = ANCHOR.plusSeconds(r.nextLong(-30L * 86_400, 30L * 86_400));
        return switch (r.nextInt(3)) {
            case 0 -> base;
            case 1 -> base.plusMillis(r.nextInt(1, 1_000));
            default -> base.plusNanos(r.nextInt(1, 1_000_000_000));
        };
    }

    /** Usually 1 to 39 characters; in boundary cases an empty, whitespace or special-character string. */
    public static String text(SplittableRandom r) {
        if (edge(r)) {
            return special(r);
        }
        return repeat(r, r.nextInt(1, SHORT_TEXT + 1));
    }

    // ---------------------------------------------------------------- helpers

    /** True in about 1 case in 10. */
    public static boolean edge(SplittableRandom r) {
        return r.nextInt(10) == 0;
    }

    /** 0 to 5 elements. */
    public static int size(SplittableRandom r) {
        return edge(r) ? pick(r, 0, MAX_ITEMS) : r.nextInt(0, MAX_ITEMS + 1);
    }

    public static <T> List<T> list(SplittableRandom r, Function<SplittableRandom, T> element) {
        int size = size(r);
        List<T> values = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            values.add(element.apply(r));
        }
        return values;
    }

    @SafeVarargs
    public static <T> T pick(SplittableRandom r, T... values) {
        return values[r.nextInt(values.length)];
    }

    public static <T> T pick(SplittableRandom r, List<T> values) {
        return values.get(r.nextInt(values.size()));
    }

    private static int pick(SplittableRandom r, int a, int b) {
        return r.nextBoolean() ? a : b;
    }

    private static int order(SplittableRandom r) {
        return edge(r) ? pick(r, 1, Integer.MAX_VALUE) : r.nextInt(1, 11);
    }

    private static String special(SplittableRandom r) {
        return pick(r, SPECIAL_STRINGS);
    }

    private static String repeat(SplittableRandom r, int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(r.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String hex(SplittableRandom r, int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(HEX.charAt(r.nextInt(HEX.length())));
        }
        return sb.toString();
    }

    private static Object jsonValue(SplittableRandom r, int depth) {
        int kinds = depth > 0 ? 8 : 6;
        return switch (r.nextInt(kinds)) {
            case 0 -> text(r);
            case 1 -> edge(r) ? pick(r, Integer.MIN_VALUE, Integer.MAX_VALUE) : r.nextInt(-1_000, 1_000);
            case 2 -> r.nextBoolean()
                    ? (long) Integer.MAX_VALUE + r.nextLong(1, Long.MAX_VALUE - Integer.MAX_VALUE)
                    : (long) Integer.MIN_VALUE - r.nextLong(1, Long.MAX_VALUE - Integer.MAX_VALUE);
            case 3 -> edge(r)
                    ? pick(r, 0.0, -0.0, Double.MAX_VALUE, Double.MIN_VALUE, -1.5e-300)
                    : r.nextDouble(-1_000.0, 1_000.0);
            case 4 -> r.nextBoolean();
            case 5 -> null;
            case 6 -> list(r, g -> jsonScalar(g));
            default -> payload(r, depth - 1);
        };
    }

    private static Object jsonScalar(SplittableRandom r) {
        return switch (r.nextInt(3)) {
            case 0 -> text(r);
            case 1 -> r.nextInt(-1_000, 1_000);
            default -> r.nextBoolean();
        };
    }
}
