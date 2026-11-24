package dev.investigator.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.investigator.config.JacksonConfig;
import dev.investigator.testsupport.InvestigatorSpringBootTest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Example tests for {@code POST /api/v1/alerts} (Req 4.1, 4.2, 4.5, 4.8, 4.9, 4.10). */
@InvestigatorSpringBootTest
@AutoConfigureMockMvc
class AlertControllerTest {

    private static final String DEMO_ALERT = """
            {
              "alert": "HighErrorRate",
              "service": "checkout-api",
              "severity": "critical",
              "detectedAt": "2026-11-23T14:40:00Z",
              "labels": { "environment": "production", "region": "ap-south-1" },
              "dashboardLinks": ["http://localhost:3000/d/checkout"]
            }
            """;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void demoAlertIsAcceptedWithIncidentIdStatusAndReportUrl() throws Exception {
        postAlert(DEMO_ALERT)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.incidentId", matchesPattern("inc-[0-9a-f]{12}")))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.reportUrl", matchesPattern("/api/v1/incidents/inc-[0-9a-f]{12}/report")));
    }

    @Test
    void extraFieldsAreIgnored() throws Exception {
        String body = DEMO_ALERT.replaceFirst("\\{", "{ \"fingerprint\": \"abc\", \"extra\": { \"n\": 1 },");
        postAlert(body).andExpect(status().isAccepted());
    }

    @Test
    void missingAndBlankRequiredFieldsAreListed() throws Exception {
        postAlert(json(Map.of("service", " ", "severity", "critical")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", contains("alert", "detectedAt", "service")));
    }

    @Test
    void fiftyLabelsAreAcceptedAndFiftyOneAreRejected() throws Exception {
        postAlert(json(alertWith("labels", labels(50)))).andExpect(status().isAccepted());
        postAlert(json(alertWith("labels", labels(51))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", contains("labels")));
    }

    @Test
    void tenLinksAreAcceptedAndElevenAreRejected() throws Exception {
        postAlert(json(alertWith("dashboardLinks", links(10)))).andExpect(status().isAccepted());
        postAlert(json(alertWith("dashboardLinks", links(11))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", contains("dashboardLinks")));
    }

    @Test
    void nonHttpLinksAreRejectedNamingTheEntry() throws Exception {
        List<String> bad = List.of("https://grafana.example/d/x", "ftp://example.com/x", "/relative", "http:opaque");
        postAlert(json(alertWith("dashboardLinks", bad)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field",
                        contains("dashboardLinks[1]", "dashboardLinks[2]", "dashboardLinks[3]")));
    }

    @Test
    void malformedJsonGivesGenericErrorWithoutEchoingTheBody() throws Exception {
        String response = postAlert("{\"alert\": \"secret-marker-7f3a\", \"service\": ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value(ApiExceptionHandler.UNREADABLE_BODY_MESSAGE))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("secret-marker-7f3a");
    }

    @Test
    void wrongJsonTypeNamesTheTopLevelField() throws Exception {
        postAlert(json(alertWith("labels", List.of("secret-marker-7f3a"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", contains("labels")));
    }

    @Test
    void bodyOver64KbIsRejectedWith413() throws Exception {
        String padding = "x".repeat(RequestSizeLimitFilter.MAX_BODY_BYTES);
        postAlert(json(alertWith("extra", padding)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.errors[0].message").value(RequestSizeLimitFilter.TOO_LARGE_MESSAGE));
    }

    @Test
    void bodyWithoutContentLengthIsCountedAndRejectedOver64Kb() throws Exception {
        RequestSizeLimitFilter filter = new RequestSizeLimitFilter(JacksonConfig.jsonCodec());

        MockHttpServletResponse tooLarge = filterWithoutContentLength(
                filter, new byte[RequestSizeLimitFilter.MAX_BODY_BYTES + 1]);
        assertThat(tooLarge.getStatus()).isEqualTo(413);

        byte[] atLimit = new byte[RequestSizeLimitFilter.MAX_BODY_BYTES];
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse accepted = new MockHttpServletResponse();
        filter.doFilter(chunkedRequest(atLimit), accepted, chain);
        assertThat(accepted.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(chain.getRequest().getInputStream().readAllBytes()).hasSize(atLimit.length);
    }

    private static MockHttpServletResponse filterWithoutContentLength(RequestSizeLimitFilter filter, byte[] body)
            throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(chunkedRequest(body), response, chain);
        assertThat(chain.getRequest()).isNull();
        return response;
    }

    /** A request that reports no Content-Length, as with chunked transfer encoding. */
    private static MockHttpServletRequest chunkedRequest(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/alerts") {
            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(body);
        return request;
    }

    private ResultActions postAlert(String body) throws Exception {
        return mvc.perform(post("/api/v1/alerts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.getBytes(StandardCharsets.UTF_8)));
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private static Map<String, Object> alertWith(String field, Object value) {
        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("alert", "HighErrorRate");
        alert.put("service", "checkout-api");
        alert.put("severity", "critical");
        alert.put("detectedAt", "2026-11-23T14:40:00Z");
        alert.put(field, value);
        return alert;
    }

    private static Map<String, String> labels(int count) {
        Map<String, String> labels = new LinkedHashMap<>();
        IntStream.range(0, count).forEach(i -> labels.put("k" + i, "v" + i));
        return labels;
    }

    private static List<String> links(int count) {
        return IntStream.range(0, count).mapToObj(i -> "https://grafana.example/d/" + i).toList();
    }
}
