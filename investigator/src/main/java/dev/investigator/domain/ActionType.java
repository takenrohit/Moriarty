package dev.investigator.domain;

/** Type of a recommended action (architecture §4). */
public enum ActionType {
    INVESTIGATE,
    ROLLBACK,
    RESTART,
    TRAFFIC_SHIFT,
    CONFIG_CHANGE,
    ESCALATE
}
