package dev.investigator.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/**
 * Body of {@code POST /api/v1/alerts}: a Mock_Alert as defined in {@code demo-scenario.md}.
 *
 * <p>Bean validation covers presence and size limits (Req 4.5, 4.8). The severity value and the
 * timestamp format are parsed by the AlertNormalizer (Req 4.6, 4.7). Fields beyond these six are
 * ignored by the JSON_Codec (Req 4.10).
 *
 * @param alert alert name, for example {@code HighErrorRate}
 * @param service affected service
 * @param severity one of {@code critical}, {@code high}, {@code warning}, {@code info}, any case
 * @param detectedAt ISO-8601 timestamp with a zone offset
 * @param labels optional labels, at most 50 entries
 * @param dashboardLinks optional absolute http(s) URLs, at most 10 entries
 */
public record MockAlertRequest(
        @NotBlank String alert,
        @NotBlank String service,
        @NotBlank String severity,
        @NotBlank String detectedAt,
        @Size(max = 50) Map<String, String> labels,
        @Size(max = 10) List<@HttpUrl String> dashboardLinks) {
}
