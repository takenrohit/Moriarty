package dev.investigator.mock;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Failure_Injection_API: {@code POST /inject/slow?enabled=true&ms=5000} makes payment-provider
 * sleep before answering; {@code enabled=false} resets it (Req 2.5, 2.7). Served in every role
 * but only the provider applies the delay.
 */
@RestController
class FailureInjectionController {

    static final long MAX_SLOW_MS = 60_000;

    private final FailureInjection failureInjection;

    FailureInjectionController(FailureInjection failureInjection) {
        this.failureInjection = failureInjection;
    }

    @PostMapping("/inject/slow")
    ResponseEntity<Map<String, Object>> slow(
            @RequestParam boolean enabled,
            @RequestParam(defaultValue = "5000") long ms) {
        if (!enabled) {
            failureInjection.reset();
        } else if (ms < 1 || ms > MAX_SLOW_MS) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "ms must be between 1 and " + MAX_SLOW_MS));
        } else {
            failureInjection.enableSlow(ms);
        }
        long current = failureInjection.slowMs();
        return ResponseEntity.ok(Map.of("slow", current > 0, "ms", current));
    }
}
