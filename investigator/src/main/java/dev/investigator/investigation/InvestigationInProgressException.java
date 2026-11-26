package dev.investigator.investigation;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A re-investigation was requested while the Incident's Investigation is still active: HTTP 409 (Req 9.3). */
@ResponseStatus(HttpStatus.CONFLICT)
public class InvestigationInProgressException extends RuntimeException {

    public InvestigationInProgressException(String incidentId) {
        super("an investigation is already in progress for " + incidentId);
    }
}
