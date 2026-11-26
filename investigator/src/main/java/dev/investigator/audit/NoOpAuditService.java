package dev.investigator.audit;

import dev.investigator.domain.Incident;
import dev.investigator.domain.InvestigationReport;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** {@link AuditService} used without the {@code postgres} profile: Phase 1 behaviour, nothing is written. */
@Service
@Profile("!postgres")
public class NoOpAuditService implements AuditService {

    @Override
    public void recordAlert(String source, Object payload, Incident incident) {
    }

    @Override
    public void recordToolCall(ToolCall call) {
    }

    @Override
    public void recordLlmExchange(LlmExchange exchange) {
    }

    @Override
    public void recordReport(InvestigationReport report) {
    }

    @Override
    public void recordApproval(Approval approval) {
    }

    @Override
    public void recordDraftRejection(DraftRejection rejection) {
    }
}
