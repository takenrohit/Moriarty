package dev.investigator.mock;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"mock.role=provider", "OTEL_SERVICE_NAME=payment-provider"})
@AutoConfigureMockMvc
@AutoConfigureObservability(tracing = false)
class ProviderRoleTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    FailureInjection failureInjection;

    @Test
    void authorizeReturns200AndMetricCarriesServiceAndStatus() throws Exception {
        mvc.perform(post("/authorize")).andExpect(status().isOk());

        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("http_server_requests_seconds_count")))
                .andExpect(content().string(containsString("service=\"payment-provider\"")))
                .andExpect(content().string(containsString("status=\"200\"")));
    }

    @Test
    void injectSlowValidatesAndResets() throws Exception {
        mvc.perform(post("/inject/slow").param("enabled", "true").param("ms", "0"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/inject/slow").param("enabled", "true").param("ms", "1500"))
                .andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(failureInjection.slowMs()).isEqualTo(1500);

        mvc.perform(post("/inject/slow").param("enabled", "false"))
                .andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(failureInjection.slowMs()).isZero();
    }
}
