package dev.investigator.mock;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** payment-provider: {@code POST /authorize} returns 200, after the injected delay if one is set. */
@RestController
@ConditionalOnProperty(name = "mock.role", havingValue = "provider")
class ProviderController {

    private final FailureInjection failureInjection;

    ProviderController(FailureInjection failureInjection) {
        this.failureInjection = failureInjection;
    }

    @PostMapping("/authorize")
    Map<String, Object> authorize() throws InterruptedException {
        long delayMs = failureInjection.slowMs();
        if (delayMs > 0) {
            Thread.sleep(delayMs);
        }
        return Map.of("authorized", true);
    }
}
