package dev.investigator.dedup;

import dev.investigator.audit.AuditService;
import dev.investigator.domain.Incident;
import dev.investigator.domain.Investigation;
import dev.investigator.domain.InvestigationStatus;
import dev.investigator.investigation.InvestigationOrchestrator;
import dev.investigator.store.IncidentRepository;
import dev.investigator.store.InvestigationRepository;
import java.util.Objects;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Dedup_Service: decides whether a normalized alert starts a new investigation or attaches to an open
 * Incident with the same Fingerprint (Req 11.1 to 11.5, CP-50).
 *
 * <p>Under the {@link FingerprintLock} for the candidate's Fingerprint it looks up
 * {@link IncidentRepository#findOpenByFingerprint} ({@code OPEN} or {@code INVESTIGATING}) and attaches to
 * the newest whose Investigation is {@code PENDING} or {@code RUNNING} (or not yet created),
 * which closes the short gap between the Investigation ending and the Incident becoming
 * {@code REPORTED}. A match records the raw alert against the existing
 * Incident in the audit trail and starts nothing; otherwise the candidate is stored, its alert recorded and
 * its investigation started, as in Phase 1. A candidate without a Fingerprint is never deduplicated.
 */
@Service
public class DedupService {

    private static final Logger log = LoggerFactory.getLogger(DedupService.class);

    private final IncidentRepository incidents;
    private final InvestigationRepository investigations;
    private final InvestigationOrchestrator orchestrator;
    private final AuditService audit;
    private final FingerprintLock lock;

    public DedupService(IncidentRepository incidents, InvestigationRepository investigations,
            InvestigationOrchestrator orchestrator, AuditService audit, FingerprintLock lock) {
        this.incidents = incidents;
        this.investigations = investigations;
        this.orchestrator = orchestrator;
        this.audit = audit;
        this.lock = lock;
    }

    /**
     * Admits one normalized alert.
     *
     * @param candidate the Incident normalized from the alert, not yet stored
     * @param source    alert source recorded in the audit trail, for example {@code mock}
     * @param payload   the alert as received, recorded in the audit trail
     */
    public Admission admit(Incident candidate, String source, Object payload) {
        return admit(candidate, source, payload, incident -> { });
    }

    /**
     * Admits one normalized alert, running {@code beforeStart} only when it would start a new investigation,
     * before anything is stored. {@code beforeStart} may throw to refuse the start (the investigation rate
     * limit, Req 6.2); an alert attached to an open Incident never runs it.
     */
    public Admission admit(Incident candidate, String source, Object payload, Consumer<Incident> beforeStart) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(beforeStart, "beforeStart");
        if (candidate.fingerprint() == null) {
            return start(candidate, source, payload, beforeStart);
        }
        return lock.withLock(candidate.fingerprint(), () -> {
            // The Investigation ends just before its Incident becomes REPORTED (or the Incident stays open if
            // that update failed); an ended one is not open, so skip it and look at the others.
            for (Incident existing : incidents.findOpenByFingerprint(candidate.fingerprint())) {
                InvestigationStatus status = investigations.find(existing.id())
                        .map(Investigation::status).orElse(InvestigationStatus.PENDING);
                if (isActive(status)) {
                    audit.recordAlert(source, payload, existing);
                    log.info("duplicate alert attached incident_id={} fingerprint={} source={}",
                            existing.id(), candidate.fingerprint(), source);
                    return new Admission(existing.id(), status, true);
                }
            }
            return start(candidate, source, payload, beforeStart);
        });
    }

    private static boolean isActive(InvestigationStatus status) {
        return status == InvestigationStatus.PENDING || status == InvestigationStatus.RUNNING;
    }

    private Admission start(Incident candidate, String source, Object payload, Consumer<Incident> beforeStart) {
        beforeStart.accept(candidate);
        incidents.save(candidate);
        audit.recordAlert(source, payload, candidate);
        Investigation investigation = orchestrator.start(candidate);
        return new Admission(candidate.id(), investigation.status(), false);
    }

    /**
     * Result of {@link #admit}.
     *
     * @param incidentId the new Incident's ID, or the existing one when attached
     * @param status     the Investigation status at the time of admission
     * @param attached   true if the alert was attached to an open Incident and no investigation started
     */
    public record Admission(String incidentId, InvestigationStatus status, boolean attached) {
    }
}
