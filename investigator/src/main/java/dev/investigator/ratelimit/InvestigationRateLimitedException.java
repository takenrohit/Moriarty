package dev.investigator.ratelimit;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * The investigation bucket of the alert's service is empty: the alert is rejected with 429 and nothing is
 * stored, so the sender can retry later (Req 6.2, design "Rate-limit rejection").
 */
@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
public class InvestigationRateLimitedException extends RuntimeException {

    public InvestigationRateLimitedException(String service) {
        super("investigation rate limit reached for service " + service);
    }
}
