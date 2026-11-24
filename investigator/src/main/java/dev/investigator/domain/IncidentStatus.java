package dev.investigator.domain;

/** Lifecycle of an incident (architecture §4). */
public enum IncidentStatus {
    OPEN,
    INVESTIGATING,
    REPORTED,
    RESOLVED
}
