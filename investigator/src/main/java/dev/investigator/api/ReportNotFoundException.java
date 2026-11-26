package dev.investigator.api;

/** No stored report for the Incident ID; mapped to 404 by {@link ApiExceptionHandler} (Req 8.2). */
public class ReportNotFoundException extends RuntimeException {

    public ReportNotFoundException(String incidentId) {
        super("no report for " + incidentId);
    }
}
