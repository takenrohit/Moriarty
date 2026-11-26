package dev.investigator.integration.http;

import dev.investigator.config.IntegrationProperties;
import dev.investigator.config.IntegrationProperties.Endpoint;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Builds the {@link RestClient} of each real integration from {@link IntegrationProperties}: base URL,
 * bearer or basic credential, and connect/read timeouts (Req 3.6, 7.5, 7.6).
 *
 * <p>Each real client calls its method once, at construction, so there is one {@code RestClient} per
 * integration. Credentials go into default request headers only; they are never logged.
 */
@Component
public class RestClients {

    private final IntegrationProperties properties;

    public RestClients(IntegrationProperties properties) {
        this.properties = properties;
    }

    public RestClient metrics() {
        return build("metrics", properties.metrics());
    }

    public RestClient logs() {
        return build("logs", properties.logs());
    }

    public RestClient traces() {
        return build("traces", properties.traces());
    }

    public RestClient changes() {
        return build("changes", properties.changes());
    }

    /** A client for {@code endpoint}; fails naming the property when its base URL is blank (Req 7.7). */
    static RestClient build(String integration, Endpoint endpoint) {
        if (!endpoint.configured()) {
            throw new IllegalStateException(IntegrationProperties.baseUrlProperty(integration) + " is not set");
        }
        // HTTP/1.1 avoids the h2c upgrade attempt the JDK client makes on plain http.
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(endpoint.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(http);
        requestFactory.setReadTimeout(endpoint.readTimeout());

        RestClient.Builder builder = RestClient.builder()
                .baseUrl(endpoint.baseUrl().strip())
                .requestFactory(requestFactory);
        if (StringUtils.hasText(endpoint.bearerToken())) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + endpoint.bearerToken().strip());
        } else if (StringUtils.hasText(endpoint.username())) {
            builder.defaultHeaders(h -> h.setBasicAuth(endpoint.username(), endpoint.password(),
                    StandardCharsets.UTF_8));
        }
        return builder.build();
    }
}
