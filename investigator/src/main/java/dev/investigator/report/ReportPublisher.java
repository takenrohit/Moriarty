package dev.investigator.report;

import dev.investigator.domain.Incident;
import dev.investigator.domain.InvestigationReport;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Report_Publisher: renders each stored report once and hands it to every {@link ReportChannel} bean in
 * {@code @Order} order: the {@link LogReportChannel} always, the Slack channel when configured
 * (Req 13.1, 13.3, 17.9, CP-47).
 *
 * <p>Publishing never throws. A rendering failure is logged as an error; a channel failure is logged at
 * WARN with the channel name, the incident ID and the exception message (which channels keep free of
 * secrets), and the remaining channels still run. Neither can undo or block the stored report (Req 13.4).
 */
@Component
public class ReportPublisher {

    private static final Logger log = LoggerFactory.getLogger(ReportPublisher.class);

    private final ReportRenderer renderer;
    private final List<ReportChannel> channels;

    public ReportPublisher(ReportRenderer renderer, List<ReportChannel> channels) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.channels = List.copyOf(channels);
    }

    /** Names of the active channels in publishing order. */
    public List<String> channelNames() {
        return channels.stream().map(ReportChannel::name).toList();
    }

    public void publish(Incident incident, InvestigationReport report) {
        String incidentId = report == null ? null : report.incidentId();
        ReportView view;
        try {
            view = renderer.view(incident, report);
        } catch (RuntimeException e) {
            log.error("could not render the report incident_id={}", incidentId, e);
            return;
        }
        for (ReportChannel channel : channels) {
            try {
                channel.publish(report, view);
            } catch (RuntimeException e) {
                log.warn("report channel failed channel={} incident_id={} reason={}: {}", channel.name(), incidentId,
                        e.getClass().getSimpleName(), e.getMessage());
            }
        }
    }
}
