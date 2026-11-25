package dev.investigator.report;

import dev.investigator.domain.Incident;
import dev.investigator.domain.InvestigationReport;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Report_Publisher: writes the Markdown rendering of each stored report to the application log at
 * INFO (Req 17.9). Phase 1 has no other channel; Slack publishing comes later.
 *
 * <p>Publishing never throws: a rendering failure is logged as an error so it cannot undo or block the
 * stored result.
 */
@Component
public class ReportPublisher {

    private static final Logger log = LoggerFactory.getLogger(ReportPublisher.class);

    private final ReportRenderer renderer;

    public ReportPublisher(ReportRenderer renderer) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    public void publish(Incident incident, InvestigationReport report) {
        try {
            String markdown = renderer.markdown(incident, report);
            log.info("investigation report incident_id={} status={}\n{}", report.incidentId(), report.status(),
                    markdown);
        } catch (RuntimeException e) {
            log.error("could not render the report for the log incident_id={}",
                    report == null ? null : report.incidentId(), e);
        }
    }
}
