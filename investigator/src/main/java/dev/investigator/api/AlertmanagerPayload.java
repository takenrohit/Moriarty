package dev.investigator.api;

import java.util.List;
import java.util.Map;

/**
 * Body of {@code POST /api/v1/alerts/alertmanager}: an Alertmanager v4 webhook payload, reduced to the
 * fields the normalizer reads (Req 8.1). Other fields ({@code groupKey}, {@code receiver},
 * {@code commonLabels}, ...) are ignored by the JSON_Codec.
 *
 * @param version payload schema version, {@code "4"}
 * @param status group status, {@code firing} or {@code resolved}
 * @param alerts the grouped alerts
 */
public record AlertmanagerPayload(String version, String status, List<AlertmanagerAlert> alerts) {

    /**
     * One alert of an Alertmanager v4 payload.
     *
     * @param status {@code firing} or {@code resolved}
     * @param labels alert labels, including {@code alertname}
     * @param annotations alert annotations
     * @param startsAt RFC 3339 time the alert started firing
     * @param endsAt RFC 3339 time the alert resolved, or the zero time
     * @param generatorURL link to the alert expression in Prometheus
     */
    public record AlertmanagerAlert(String status, Map<String, String> labels, Map<String, String> annotations,
            String startsAt, String endsAt, String generatorURL) {
    }
}
