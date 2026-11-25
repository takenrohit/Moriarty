package dev.investigator.report;

import dev.investigator.domain.ActionType;
import dev.investigator.domain.RecommendedAction;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Action_Policy: Java decides which recommended actions need human approval, replacing the model's
 * value (Req 16.4 to 16.6, FR-41).
 *
 * <p>Only {@code INVESTIGATE} and {@code ESCALATE} run without approval; every other type, and a
 * missing type, requires it. A replaced value that differs from the input is logged at WARN with the
 * Incident_ID and the action order.
 */
public final class ActionPolicy {

    private static final Logger log = LoggerFactory.getLogger(ActionPolicy.class);

    /** True unless {@code type} is {@code INVESTIGATE} or {@code ESCALATE}. */
    public static boolean requiresApproval(ActionType type) {
        return type != ActionType.INVESTIGATE && type != ActionType.ESCALATE;
    }

    /**
     * Returns the actions in the same order with {@code requiresApproval} set by
     * {@link #requiresApproval(ActionType)}; all other fields are unchanged. Applying it twice gives the
     * same list.
     */
    public List<RecommendedAction> apply(String incidentId, List<RecommendedAction> actions) {
        Objects.requireNonNull(actions, "actions");
        return actions.stream().map(action -> apply(incidentId, action)).toList();
    }

    private static RecommendedAction apply(String incidentId, RecommendedAction action) {
        boolean required = requiresApproval(action.type());
        if (action.requiresApproval() == required) {
            return action;
        }
        log.warn("action policy replaced requiresApproval incident_id={} action_order={} type={} draft={} policy={}",
                incidentId, action.order(), action.type(), action.requiresApproval(), required);
        return new RecommendedAction(action.order(), action.type(), action.description(), required,
                action.evidenceIds());
    }
}
