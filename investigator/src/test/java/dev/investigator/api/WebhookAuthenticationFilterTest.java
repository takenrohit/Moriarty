package dev.investigator.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.investigator.config.JacksonConfig;
import dev.investigator.config.WebhookProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Example tests for the Webhook_Authenticator (Req 10.1, 10.2, 10.4 to 10.7). The filter is exercised
 * directly: a request that reaches the chain would reach the controllers, one that does not creates no
 * Incident. The no-secret pass-through on the real endpoints is also covered by the controller tests,
 * which run with no secret configured.
 */
class WebhookAuthenticationFilterTest {

    private static final String SECRET = "s3cret-token";

    private final WebhookAuthenticationFilter withSecret = filter(SECRET);

    @Test
    void matchingBearerOrTokenHeaderPasses() throws Exception {
        assertPasses(withSecret, request("POST", "/api/v1/alerts/alertmanager", "Authorization", "Bearer " + SECRET));
        assertPasses(withSecret, request("POST", "/api/v1/alerts/grafana", "Authorization", "bearer " + SECRET));
        assertPasses(withSecret, request("POST", "/api/v1/alerts", "X-Webhook-Token", SECRET));
    }

    @Test
    void missingOrWrongCredentialGives401WithoutEchoingIt() throws Exception {
        assertRejected(request("POST", "/api/v1/alerts/alertmanager", null, null));
        MockHttpServletResponse wrong = assertRejected(
                request("POST", "/api/v1/alerts/grafana", "Authorization", "Bearer secret-marker-7f3a"));
        assertThat(wrong.getContentAsString())
                .contains(WebhookAuthenticationFilter.UNAUTHORIZED_MESSAGE)
                .doesNotContain("secret-marker-7f3a");
        assertRejected(request("POST", "/api/v1/alerts", "X-Webhook-Token", SECRET + "x"));
        assertRejected(request("POST", "/api/v1/alerts", "Authorization", "Basic " + SECRET));
        // Encoded or decorated paths still reach the alert controllers, so they are challenged too.
        assertRejected(request("POST", "/api/v1/%61lerts/grafana", null, null));
        assertRejected(request("POST", "/api/v1/alerts;x=y", null, null));
    }

    @Test
    void readOnlyAndHealthEndpointsAreNeverChallenged() throws Exception {
        assertPasses(withSecret, request("GET", "/actuator/health", null, null));
        assertPasses(withSecret, request("GET", "/api/v1/incidents/inc-000000000001/report", null, null));
        assertPasses(withSecret, request("GET", "/incidents", null, null));
        assertPasses(withSecret, request("GET", "/api/v1/alerts", null, null));
    }

    @Test
    void noSecretLetsAlertRequestsThrough() throws Exception {
        assertPasses(filter(null), request("POST", "/api/v1/alerts/alertmanager", null, null));
        assertPasses(filter("  "), request("POST", "/api/v1/alerts", "Authorization", "Bearer anything"));
    }

    @Test
    void propertiesMaskTheSecret() {
        assertThat(new WebhookProperties(SECRET, "service").toString())
                .doesNotContain(SECRET)
                .contains("secret=****");
    }

    private static WebhookAuthenticationFilter filter(String secret) {
        return new WebhookAuthenticationFilter(new WebhookProperties(secret, "service"), JacksonConfig.jsonCodec());
    }

    private static MockHttpServletRequest request(String method, String uri, String header, String value) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        if (header != null) {
            request.addHeader(header, value);
        }
        return request;
    }

    private static void assertPasses(WebhookAuthenticationFilter filter, MockHttpServletRequest request)
            throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(chain.getRequest()).as("%s %s reaches the chain", request.getMethod(), request.getRequestURI())
                .isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private MockHttpServletResponse assertRejected(MockHttpServletRequest request) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();
        withSecret.doFilter(request, response, chain);
        assertThat(chain.getRequest()).as("%s %s is stopped", request.getMethod(), request.getRequestURI()).isNull();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        return response;
    }
}
