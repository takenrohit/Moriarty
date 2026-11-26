package dev.investigator.integration.changes;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import dev.investigator.config.IntegrationProperties;
import dev.investigator.config.IntegrationProperties.Endpoint;
import dev.investigator.config.InvestigatorProperties;
import dev.investigator.integration.http.IntegrationCallException;
import dev.investigator.integration.http.RestClients;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * {@link GitHubChangeClient} against a WireMock GitHub: deployments with their commit and commit-pulls lookups
 * (paths, bearer token, GitHub headers), an HTTP error, rate-limit exhaustion, the token never in output or
 * messages, and the real Fake GitHub stubs from {@code deploy/fake-github} (Req 6.1, 6.3 to 6.6, 14.4). The token
 * is a test value; WireMock runs in-process. Tagged {@code containers} like every {@code *IT} (CP-55).
 */
@Tag("containers")
@ExtendWith(OutputCaptureExtension.class)
class GitHubClientIT {

    private static final WireMockServer GITHUB = new WireMockServer(options().dynamicPort());

    private static final String TOKEN = "ghp_testOnlyTokenValue0123456789abcdef";
    private static final String REPO = "/repos/moriarty-demo/checkout-platform";
    private static final Instant SINCE = Instant.parse("2026-11-22T14:40:00Z");
    private static final Instant UNTIL = Instant.parse("2026-11-23T14:40:00Z");
    private static final String SHA_NEW = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String SHA_OLD = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    private static final String DEPLOYMENTS = """
            [
              {"id":3,"sha":"%1$s","ref":"checkout-api-v2.14.3","environment":"production",
               "payload":{"service":"checkout-api","version":"v2.14.3"},
               "creator":{"login":"dev-alice"},"created_at":"2026-11-23T14:10:00Z"},
              {"id":2,"sha":"cccccccccccccccccccccccccccccccccccccccc","ref":"payment-gateway-v1.4.0",
               "payload":{"service":"payment-gateway","version":"v1.4.0"},
               "creator":{"login":"dev-carol"},"created_at":"2026-11-23T12:00:00Z"},
              {"id":1,"sha":"%2$s","ref":"checkout-api-v2.14.2","environment":"production",
               "payload":{"service":"checkout-api"},
               "creator":{"login":"dev-bob"},"created_at":"2026-11-23T02:00:00Z"},
              {"id":0,"sha":"dddddddddddddddddddddddddddddddddddddddd","ref":"checkout-api-v2.14.1",
               "payload":{"service":"checkout-api","version":"v2.14.1"},
               "creator":{"login":"dev-bob"},"created_at":"2026-11-21T10:00:00Z"}
            ]
            """.formatted(SHA_NEW, SHA_OLD);

    private static final String COMMIT_NEW = """
            {"sha":"%s","html_url":"https://github.com/moriarty-demo/checkout-platform/commit/%1$s",
             "commit":{"message":"Update order confirmation copy (#1423)\\n\\nCopy only."},
             "files":[{"filename":"checkout-api/src/main/resources/messages.properties"},
                      {"filename":"checkout-api/src/main/resources/templates/confirmation.html"}]}
            """.formatted(SHA_NEW);

    private static final String PULLS_NEW = """
            [{"number":1423,"title":"Update order confirmation copy",
              "html_url":"https://github.com/moriarty-demo/checkout-platform/pull/1423",
              "merged_at":"2026-11-23T14:00:00Z"}]
            """;

    private static final InvestigatorProperties PROPERTIES = new Binder(new MapConfigurationPropertySource())
            .bindOrCreate("investigator", InvestigatorProperties.class);

    @BeforeAll
    static void start() {
        GITHUB.start();
    }

    @AfterAll
    static void stop() {
        GITHUB.stop();
    }

    @BeforeEach
    void reset() {
        GITHUB.resetAll();
    }

    @Test
    void deploymentsWithCommitsAreMappedNewestFirstWithTokenAndGitHubHeaders(CapturedOutput output) {
        GITHUB.stubFor(get(urlPathEqualTo(REPO + "/deployments")).willReturn(json(200, DEPLOYMENTS)));
        GITHUB.stubFor(get(urlPathEqualTo(REPO + "/commits/" + SHA_NEW)).willReturn(json(200, COMMIT_NEW)));
        GITHUB.stubFor(get(urlPathEqualTo(REPO + "/commits/" + SHA_NEW + "/pulls")).willReturn(json(200, PULLS_NEW)));
        // SHA_OLD's commit and pulls are not stubbed: WireMock answers 404, an unknown commit.

        List<ChangeRecord> records = client(GITHUB.baseUrl()).recentChanges("checkout-api", SINCE, UNTIL);

        assertThat(records).containsExactly(
                new ChangeRecord("checkout-api", "v2.14.3", Instant.parse("2026-11-23T14:10:00Z"), "dev-alice",
                        "Update order confirmation copy",
                        "https://github.com/moriarty-demo/checkout-platform/pull/1423",
                        List.of("checkout-api/src/main/resources/messages.properties",
                                "checkout-api/src/main/resources/templates/confirmation.html")),
                new ChangeRecord("checkout-api", "checkout-api-v2.14.2", Instant.parse("2026-11-23T02:00:00Z"),
                        "dev-bob", null, null, List.of()));
        for (String path : List.of("/deployments", "/commits/" + SHA_NEW, "/commits/" + SHA_NEW + "/pulls",
                "/commits/" + SHA_OLD, "/commits/" + SHA_OLD + "/pulls")) {
            GITHUB.verify(1, getRequestedFor(urlPathEqualTo(REPO + path))
                    .withHeader("Authorization", equalTo("Bearer " + TOKEN))
                    .withHeader("Accept", equalTo("application/vnd.github+json"))
                    .withHeader("X-GitHub-Api-Version", equalTo("2022-11-28")));
        }
        GITHUB.verify(1, getRequestedFor(urlPathEqualTo(REPO + "/deployments"))
                .withQueryParam("per_page", equalTo("100")));
        assertThat(GITHUB.getAllServeEvents()).hasSize(5);
        assertThat(output.getAll()).doesNotContain(TOKEN);
    }

    @Test
    void httpErrorThrowsWithoutTheToken(CapturedOutput output) {
        GITHUB.stubFor(get(urlPathEqualTo(REPO + "/deployments"))
                .willReturn(json(401, "{\"message\":\"Bad credentials\"}")));

        assertThatThrownBy(() -> client(GITHUB.baseUrl()).recentChanges("checkout-api", SINCE, UNTIL))
                .isInstanceOfSatisfying(IntegrationCallException.class, e -> {
                    assertThat(e.integration()).isEqualTo("changes");
                    assertThat(e.hint()).isNull();
                })
                .hasMessageContaining("HTTP 401")
                .hasMessageContaining("Bad credentials")
                .message().doesNotContain(TOKEN);
        assertThat(output.getAll()).doesNotContain(TOKEN);
    }

    @Test
    void exhaustedRateLimitThrowsNamingTheRateLimitWithAHint(CapturedOutput output) {
        GITHUB.stubFor(get(urlPathEqualTo(REPO + "/deployments")).willReturn(json(403,
                "{\"message\":\"API rate limit exceeded\"}")
                .withHeader("X-RateLimit-Limit", "5000")
                .withHeader("X-RateLimit-Remaining", "0")
                .withHeader("X-RateLimit-Reset", "1795448400")));

        assertThatThrownBy(() -> client(GITHUB.baseUrl()).recentChanges("checkout-api", SINCE, UNTIL))
                .isInstanceOfSatisfying(IntegrationCallException.class,
                        e -> assertThat(e.hint()).isEqualTo(GitHubChangeClient.RATE_LIMIT_HINT))
                .hasMessageContaining("rate limit exhausted")
                .hasMessageContaining("2026-11-23T15:40:00Z")
                .message().doesNotContain(TOKEN);
        assertThat(output.getAll()).doesNotContain(TOKEN);
    }

    @Test
    void clientReadsTheFakeGitHubStubsFromDeploy() {
        WireMockServer fake = new WireMockServer(options().dynamicPort().usingFilesUnderDirectory("../deploy/fake-github"));
        fake.start();
        try {
            Instant now = Instant.now();
            List<ChangeRecord> records = client(fake.baseUrl())
                    .recentChanges("checkout-api", now.minus(Duration.ofHours(30)), now);

            assertThat(records).singleElement().satisfies(record -> {
                assertThat(record.service()).isEqualTo("checkout-api");
                assertThat(record.version()).isEqualTo("v2.14.2");
                assertThat(record.author()).isEqualTo("dev-bob");
                assertThat(record.prTitle()).isEqualTo("Add order history pagination");
                assertThat(record.link()).isEqualTo("https://github.com/moriarty-demo/checkout-platform/pull/1398");
                assertThat(record.changedFiles()).containsExactly(
                        "checkout-api/src/main/java/com/example/checkout/orders/OrderHistoryController.java",
                        "checkout-api/src/main/resources/templates/order-history.html");
                assertThat(record.deployedAt()).isCloseTo(now.minus(Duration.ofHours(26)),
                        within(5, ChronoUnit.MINUTES));
            });
            // The baseline is 26 h old, so the default 24 h look-back sees no deployment.
            assertThat(client(fake.baseUrl()).recentChanges("checkout-api", now.minus(Duration.ofHours(24)), now))
                    .isEmpty();
        } finally {
            fake.stop();
        }
    }

    private static ResponseDefinitionBuilder json(int status, String body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json; charset=utf-8")
                .withBody(body);
    }

    private static GitHubChangeClient client(String baseUrl) {
        Endpoint unset = new Endpoint(null, null, null, null, Duration.ofSeconds(2), Duration.ofSeconds(10));
        Endpoint changes = new Endpoint(baseUrl, TOKEN, null, null, Duration.ofSeconds(2), Duration.ofSeconds(10));
        return new GitHubChangeClient(new RestClients(new IntegrationProperties(unset, unset, unset, changes)),
                PROPERTIES, "moriarty-demo/checkout-platform");
    }
}
