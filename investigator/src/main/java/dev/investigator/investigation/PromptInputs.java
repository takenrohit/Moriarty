package dev.investigator.investigation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.investigator.domain.Incident;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Builds the template values for {@link InvestigatorAgent#investigate} (Req 13.4).
 *
 * <p>The Report_Draft_Schema text is read from the classpath once, when the bean is created, so a
 * missing schema file fails startup instead of the first investigation.
 */
@Component
public class PromptInputs {

    /** Classpath location of the Report_Draft_Schema. */
    public static final String REPORT_SCHEMA_RESOURCE = "schemas/report-draft.schema.json";

    private final ObjectMapper jsonCodec;
    private final String reportSchema;

    public PromptInputs(ObjectMapper jsonCodec) {
        this.jsonCodec = Objects.requireNonNull(jsonCodec, "jsonCodec");
        this.reportSchema = readResource(REPORT_SCHEMA_RESOURCE);
    }

    /** The Incident serialized with the JSON_Codec, so times are ISO-8601 UTC strings. */
    public String incidentJson(Incident incident) {
        Objects.requireNonNull(incident, "incident");
        try {
            return jsonCodec.writeValueAsString(incident);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize incident " + incident.id(), e);
        }
    }

    /** An Investigation_Window bound in the form the tools accept, for example {@code 2026-11-23T13:40:00Z}. */
    public static String isoInstant(Instant instant) {
        return Objects.requireNonNull(instant, "instant").toString();
    }

    /** The Report_Draft_Schema text, as stored in {@value #REPORT_SCHEMA_RESOURCE}. */
    public String reportSchema() {
        return reportSchema;
    }

    private static String readResource(String location) {
        ClassPathResource resource = new ClassPathResource(location);
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read classpath resource " + location, e);
        }
    }
}
