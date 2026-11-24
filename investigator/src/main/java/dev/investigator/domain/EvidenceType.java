package dev.investigator.domain;

/** Kind of stored evidence (architecture §4). {@code ALERT} is added so timeline entries can cite the alert (CP-1). */
public enum EvidenceType {
    METRIC,
    LOG,
    TRACE,
    DEPLOYMENT,
    RUNBOOK,
    CONFIG,
    ALERT
}
