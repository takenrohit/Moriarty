package dev.investigator.mock;

import java.net.URI;
import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Builds the RestClient a role uses to call its downstream service. */
final class Downstream {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);

    private Downstream() {
    }

    static RestClient client(RestClient.Builder builder, URI baseUrl, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(readTimeout);
        return builder.clone()
                .baseUrl(baseUrl.toString())
                .requestFactory(factory)
                .build();
    }
}
