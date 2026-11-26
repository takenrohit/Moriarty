package dev.investigator.api;

import java.util.List;
import java.util.Map;

/**
 * Body of {@code POST /api/v1/alerts/grafana}: a Grafana unified-alerting webhook contact-point payload,
 * reduced to the fields the normalizer reads (Req 9.1). Other fields ({@code orgId}, {@code title},
 * {@code message}, {@code commonLabels}, ...) are ignored by the JSON_Codec.
 *
 * @param status group status, {@code firing} or {@code resolved}
 * @param alerts the grouped alerts
 */
public record GrafanaPayload(String status, List<GrafanaAlert> alerts) {

    /**
     * One alert of a Grafana webhook payload.
     *
     * @param status {@code firing} or {@code resolved}
     * @param labels alert labels, including {@code alertname} (the rule name) and {@code grafana_folder}
     * @param annotations alert annotations
     * @param startsAt RFC 3339 evaluation time at which the alert started firing
     * @param generatorURL link to the alert rule in Grafana
     * @param dashboardURL link to the rule's dashboard, when the rule is linked to one
     * @param panelURL link to the rule's panel, when the rule is linked to one
     */
    public record GrafanaAlert(String status, Map<String, String> labels, Map<String, String> annotations,
            String startsAt, String generatorURL, String dashboardURL, String panelURL) {
    }
}
