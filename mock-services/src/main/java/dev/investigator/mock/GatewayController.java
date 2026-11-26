package dev.investigator.mock;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * payment-gateway: {@code POST /charge} calls payment-provider {@code POST /authorize} with a short
 * read timeout. A timeout becomes a 504; the agent marks the client span as an error.
 */
@RestController
@ConditionalOnProperty(name = "mock.role", havingValue = "gateway")
class GatewayController {

    private static final Logger log = LoggerFactory.getLogger(GatewayController.class);

    private final RestClient provider;

    GatewayController(RestClient.Builder builder, MockProperties properties) {
        this.provider = Downstream.client(builder, properties.downstreamUrl(), properties.gatewayReadTimeout());
    }

    @PostMapping("/charge")
    ResponseEntity<Map<String, Object>> charge(@RequestBody(required = false) Map<String, Object> body) {
        try {
            provider.post()
                    .uri("/authorize")
                    .body(body == null ? Map.of() : body)
                    .retrieve()
                    .toBodilessEntity();
            return ResponseEntity.ok(Map.of("status", "charged"));
        } catch (ResourceAccessException e) {
            log.error("payment-provider call timed out: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(Map.of("error", "provider_timeout"));
        } catch (HttpStatusCodeException e) {
            log.error("payment-provider returned {}", e.getStatusCode().value(), e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "provider_error"));
        }
    }
}
