package dev.investigator.mock;

import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** checkout-api: {@code POST /checkout} calls payment-gateway {@code POST /charge}. */
@RestController
@ConditionalOnProperty(name = "mock.role", havingValue = "checkout")
class CheckoutController {

    private static final Logger log = LoggerFactory.getLogger(CheckoutController.class);

    private final RestClient gateway;

    CheckoutController(RestClient.Builder builder, MockProperties properties) {
        this.gateway = Downstream.client(builder, properties.downstreamUrl(), properties.checkoutReadTimeout());
    }

    @PostMapping("/checkout")
    Map<String, Object> checkout() {
        String orderId = UUID.randomUUID().toString();
        try {
            gateway.post()
                    .uri("/charge")
                    .body(Map.of("orderId", orderId, "amountCents", 4999))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() == HttpStatus.GATEWAY_TIMEOUT.value()) {
                throw new PaymentGatewayTimeoutException("payment-gateway timed out charging order " + orderId, e);
            }
            throw e;
        } catch (ResourceAccessException e) {
            throw new PaymentGatewayTimeoutException("payment-gateway did not respond for order " + orderId, e);
        }
        return Map.of("status", "ok", "orderId", orderId);
    }

    @ExceptionHandler(PaymentGatewayTimeoutException.class)
    ResponseEntity<Map<String, String>> onGatewayTimeout(PaymentGatewayTimeoutException e) {
        log.error("Checkout failed with PaymentGatewayTimeoutException: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "payment_gateway_timeout", "message", e.getMessage()));
    }

    @ExceptionHandler(HttpStatusCodeException.class)
    ResponseEntity<Map<String, String>> onGatewayError(HttpStatusCodeException e) {
        log.error("Checkout failed: payment-gateway returned {}", e.getStatusCode().value(), e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "payment_gateway_error"));
    }
}
