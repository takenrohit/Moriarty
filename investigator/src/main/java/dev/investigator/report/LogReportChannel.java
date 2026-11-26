package dev.investigator.report;

import dev.investigator.domain.InvestigationReport;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Log_Channel: writes the Markdown rendering of each stored report to the application log at INFO
 * (Req 17.9). Always present, and first, so the report reaches the log even when Slack is unset or
 * fails (Req 13.3).
 */
@Component
@Order(0)
public class LogReportChannel implements ReportChannel {

    private static final Logger log = LoggerFactory.getLogger(LogReportChannel.class);

    private final ReportRenderer renderer;

    public LogReportChannel(ReportRenderer renderer) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
    }

    @Override
    public String name() {
        return "log";
    }

    @Override
    public void publish(InvestigationReport report, ReportView view) {
        log.info("investigation report incident_id={} status={}\n{}", report.incidentId(), report.status(),
                renderer.markdown(view));
    }
}
