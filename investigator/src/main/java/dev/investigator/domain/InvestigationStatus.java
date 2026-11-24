package dev.investigator.domain;

/** Lifecycle of an investigation; allowed transitions are defined by the design state machine. */
public enum InvestigationStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    INCONCLUSIVE,
    FAILED
}
