package dev.investigator.investigation;

import static dev.investigator.domain.InvestigationStatus.COMPLETED;
import static dev.investigator.domain.InvestigationStatus.FAILED;
import static dev.investigator.domain.InvestigationStatus.INCONCLUSIVE;
import static dev.investigator.domain.InvestigationStatus.PENDING;
import static dev.investigator.domain.InvestigationStatus.RUNNING;

import dev.investigator.domain.InvestigationStatus;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The Investigation status state machine (design "Investigation status state machine", Req 6.3).
 *
 * <p>Allowed: {@code PENDING→RUNNING}, {@code PENDING→FAILED}, {@code RUNNING→COMPLETED},
 * {@code RUNNING→INCONCLUSIVE}, {@code RUNNING→FAILED}. {@code COMPLETED}, {@code INCONCLUSIVE} and
 * {@code FAILED} are terminal. Self-transitions are not allowed.
 */
public final class StatusTransitions {

    private static final Map<InvestigationStatus, Set<InvestigationStatus>> ALLOWED = allowed();

    private StatusTransitions() {
    }

    /** Returns whether {@code from → to} is in the allowed set; false when either is null. */
    public static boolean isAllowed(InvestigationStatus from, InvestigationStatus to) {
        return from != null && to != null && ALLOWED.get(from).contains(to);
    }

    private static Map<InvestigationStatus, Set<InvestigationStatus>> allowed() {
        Map<InvestigationStatus, Set<InvestigationStatus>> allowed = new EnumMap<>(InvestigationStatus.class);
        for (InvestigationStatus status : InvestigationStatus.values()) {
            allowed.put(status, EnumSet.noneOf(InvestigationStatus.class));
        }
        allowed.put(PENDING, EnumSet.of(RUNNING, FAILED));
        allowed.put(RUNNING, EnumSet.of(COMPLETED, INCONCLUSIVE, FAILED));
        return allowed;
    }
}
