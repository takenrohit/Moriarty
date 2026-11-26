package dev.investigator.mock;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Baseline traffic for checkout-api: one {@code POST /checkout} to itself every ~500 ms so the
 * request rate and 5xx ratio are meaningful (CP-58). Checkout role only; disable with
 * {@code LOADGEN_ENABLED=false}. Requests run off the scheduler thread so slow checkouts do not
 * lower the rate; at most {@value #MAX_IN_FLIGHT} are in flight.
 */
@Component
@ConditionalOnExpression("'${mock.role:provider}'.equalsIgnoreCase('checkout') and ${mock.loadgen.enabled:true}")
class LoadGenerator implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(LoadGenerator.class);
    static final int MAX_IN_FLIGHT = 20;

    private final RestClient self;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore inFlight = new Semaphore(MAX_IN_FLIGHT);

    LoadGenerator(RestClient.Builder builder, @Value("${server.port:8080}") int port) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1_000);
        factory.setReadTimeout(10_000);
        this.self = builder.clone()
                .baseUrl("http://127.0.0.1:" + port)
                .requestFactory(factory)
                .build();
    }

    @Scheduled(initialDelayString = "${mock.loadgen.initial-delay-ms:10000}",
            fixedRateString = "${mock.loadgen.interval-ms:500}")
    void tick() {
        if (!inFlight.tryAcquire()) {
            return;
        }
        executor.execute(() -> {
            try {
                self.post().uri("/checkout").retrieve().toBodilessEntity();
            } catch (RestClientException e) {
                // checkout-api already logged the failure; keep the load generator quiet.
                log.debug("Load-generator checkout failed: {}", e.getMessage());
            } finally {
                inFlight.release();
            }
        });
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
