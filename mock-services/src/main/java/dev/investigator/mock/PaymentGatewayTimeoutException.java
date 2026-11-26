package dev.investigator.mock;

/** Thrown by checkout-api when payment-gateway times out (the demo failure, demo-scenario.md). */
public class PaymentGatewayTimeoutException extends RuntimeException {

    public PaymentGatewayTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
