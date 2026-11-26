package dev.investigator.mock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * One app that plays checkout-api, payment-gateway or payment-provider depending on
 * {@code APP_ROLE} (CP-56). Telemetry (traces, logs) comes from the OpenTelemetry Java agent
 * loaded by the image; metrics are scraped from {@code /actuator/prometheus} (CP-58).
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class MockServicesApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockServicesApplication.class, args);
    }
}
