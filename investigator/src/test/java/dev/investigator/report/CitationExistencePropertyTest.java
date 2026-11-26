// Feature: reliability, Property 1: Citation existence validation
package dev.investigator.report;

import static org.assertj.core.api.Assertions.assertThat;

import dev.investigator.domain.ActionType;
import dev.investigator.domain.Confidence;
import dev.investigator.domain.DraftAction;
import dev.investigator.domain.DraftHypothesis;
import dev.investigator.domain.DraftTimelineEntry;
import dev.investigator.domain.InvestigationReport;
import dev.investigator.domain.InvestigationStatus;
import dev.investigator.domain.ReportDraft;
import dev.investigator.investigation.InvestigationContext;
import dev.investigator.testsupport.PropertySeeds;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Property 1 over {@link ReportValidator#validateDraft} and {@link ReportValidator#validateReport}: for a
 * random set of stored Evidence_IDs and a draft citing known IDs, well-formed unknown IDs and IDs of
 * another incident, each well-formed unknown citation gives exactly one "does not exist" violation at its
 * path, each foreign citation only the format violation, and a draft citing only known IDs is valid.
 */
class CitationExistencePropertyTest {

    private static final Instant NOW = Instant.parse("2026-11-23T14:43:00Z");
    private static final String EXISTS = ": " + ReportValidator.UNKNOWN_EVIDENCE;

    private final ReportValidator validator = new ReportValidator();
    private final ReportAssembler assembler = new ReportAssembler();

    /** **Validates: Requirements 4.1, 4.2** */
    @ParameterizedTest(name = "seed={0}")
    @MethodSource("dev.investigator.testsupport.PropertySeeds#seeds")
    void exactlyTheUnknownWellFormedCitationsAreRejected(long seed) {
        PropertySeeds.check(seed, r -> {
            String incidentId = incidentId(r);
            String other = incidentId(r);
            Set<String> known = new LinkedHashSet<>();
            int knownCount = 1 + r.nextInt(12);
            while (known.size() < knownCount) {
                known.add(id(incidentId, 1 + r.nextInt(40)));
            }
            List<String> knownList = List.copyOf(known);
            double unknownRate = r.nextBoolean() ? 0 : 0.25;
            double foreignRate = r.nextInt(4) == 0 ? 0.1 : 0;
            Generator gen = new Generator(r, incidentId, other, knownList, known, unknownRate, foreignRate);

            List<DraftTimelineEntry> timeline = new ArrayList<>();
            for (int i = 0, n = 1 + r.nextInt(4); i < n; i++) {
                timeline.add(new DraftTimelineEntry("2026-11-23T14:38:00Z", "event " + i,
                        gen.cite("timeline", i, "evidenceIds", 1)));
            }
            List<DraftHypothesis> hypotheses = new ArrayList<>();
            for (int i = 0, n = r.nextInt(4); i < n; i++) {
                hypotheses.add(new DraftHypothesis("h" + i, Confidence.MEDIUM,
                        gen.cite("hypotheses", i, "supportingEvidenceIds", 1),
                        gen.cite("hypotheses", i, "contradictingEvidenceIds", 0), false));
            }
            List<DraftAction> actions = new ArrayList<>();
            for (int i = 0, n = r.nextInt(4); i < n; i++) {
                actions.add(new DraftAction(i + 1, ActionType.INVESTIGATE, "check " + i, false,
                        gen.cite("actions", i, "evidenceIds", 0)));
            }
            ReportDraft draft = new ReportDraft(incidentId, "summary", timeline, hypotheses, actions, List.of());

            List<String> draftViolations = validator.validateDraft(draft, incidentId, known).violations();
            assertThat(draftViolations).containsExactlyInAnyOrderElementsOf(gen.expected);
            assertThat(draftViolations.isEmpty()).isEqualTo(gen.unknown == 0 && gen.foreign == 0);
            assertThat(draftViolations).filteredOn(v -> v.endsWith(EXISTS)).hasSize(gen.unknown);

            // The final report check applies the same rule under the report's paths.
            InvestigationReport report = assembler.assemble(draft, context(incidentId, knownList.get(0)),
                    InvestigationStatus.COMPLETED, List.of(), List.of(), NOW);
            List<String> reportExpected = gen.expected.stream()
                    .map(v -> v.startsWith("$.timeline[")
                            ? "$.timeline.entries[" + v.substring("$.timeline[".length()) : v)
                    .toList();
            assertThat(validator.validateReport(report, known).violations())
                    .containsExactlyInAnyOrderElementsOf(reportExpected);
        });
    }

    /** Draws citations and records the violation each one should cause. */
    private static final class Generator {

        final SplittableRandom r;
        final String incidentId;
        final String other;
        final List<String> knownList;
        final Set<String> known;
        final double unknownRate;
        final double foreignRate;
        final List<String> expected = new ArrayList<>();
        int unknown;
        int foreign;

        Generator(SplittableRandom r, String incidentId, String other, List<String> knownList, Set<String> known,
                double unknownRate, double foreignRate) {
            this.r = r;
            this.incidentId = incidentId;
            this.other = other;
            this.knownList = knownList;
            this.known = known;
            this.unknownRate = unknownRate;
            this.foreignRate = foreignRate;
        }

        List<String> cite(String list, int index, String field, int min) {
            List<String> ids = new ArrayList<>();
            for (int j = 0, n = min + r.nextInt(3); j < n; j++) {
                String path = "$." + list + "[" + index + "]." + field + "[" + j + "]";
                double roll = r.nextDouble();
                if (roll < foreignRate) {
                    ids.add(id(other, 1 + r.nextInt(40)));
                    expected.add(path + ": is not an evidence ID of incident " + incidentId
                            + " (expected ev-" + incidentId + "-<n>)");
                    foreign++;
                } else if (roll < foreignRate + unknownRate) {
                    String id;
                    do {
                        id = id(incidentId, 1 + r.nextInt(80));
                    } while (known.contains(id));
                    ids.add(id);
                    expected.add(path + EXISTS);
                    unknown++;
                } else {
                    ids.add(knownList.get(r.nextInt(knownList.size())));
                }
            }
            return ids;
        }
    }

    private static String incidentId(SplittableRandom r) {
        byte[] bytes = new byte[6];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) r.nextInt(256);
        }
        return "inc-" + HexFormat.of().formatHex(bytes);
    }

    private static String id(String incidentId, int n) {
        return "ev-" + incidentId + "-" + n;
    }

    private static InvestigationContext context(String incidentId, String alertId) {
        Instant detected = Instant.parse("2026-11-23T14:40:00Z");
        return new InvestigationContext(incidentId, detected.minusSeconds(3600), detected.plusSeconds(600),
                NOW.plusSeconds(180), "0".repeat(31) + "1", alertId, detected, 15);
    }
}
