package dev.investigator.evidence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.investigator.config.InvestigatorProperties;
import dev.investigator.domain.Evidence;
import dev.investigator.domain.EvidenceRef;
import dev.investigator.domain.EvidenceType;
import dev.investigator.domain.Incident;
import dev.investigator.investigation.InvestigationContext;
import dev.investigator.store.EvidenceRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stores tool results and the alert as Evidence (Req 9).
 *
 * <p>Evidence_IDs are {@code ev-<incidentId>-<n>}, with {@code n} from a per-incident
 * {@link AtomicInteger} starting at 1; the ALERT Evidence gets 1 because the orchestrator stores it
 * first. The counter starts from the number of Evidence already in the repository for the incident.
 *
 * <p>Each store takes a per-incident lock and advances the counter only after the Evidence is saved,
 * so a failing summarizer, redactor or save leaves no gap and concurrent stores get distinct
 * consecutive IDs (Req 9.2, 9.3). The work under the lock is in-memory only; a
 * {@link ReentrantLock} is used so virtual threads are not pinned.
 */
@Component
public class EvidenceService {

    static final String ALERT_SOURCE = "alert-intake";
    private static final String HEADLINE_KEY = "headline";

    private static final Logger log = LoggerFactory.getLogger(EvidenceService.class);
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final EvidenceRepository repository;
    private final Redactor redactor;
    private final ObjectMapper mapper;
    private final int maxResultBytes;
    private final ConcurrentMap<String, Sequence> sequences = new ConcurrentHashMap<>();

    public EvidenceService(EvidenceRepository repository, Redactor redactor, ObjectMapper mapper,
            InvestigatorProperties properties) {
        this.repository = repository;
        this.redactor = redactor;
        this.mapper = mapper;
        this.maxResultBytes = properties.tools().maxResultBytes();
    }

    /**
     * Assigns the next Evidence_ID, builds the summary for it, redacts, caps and saves it, and only
     * then returns (Req 9.1). The returned {@link Evidence#summary()} is the capped Tool_Result; the
     * redaction status is returned alongside for the tool log entry (Req 18.3).
     *
     * @param citation the query or URL that reproduces the result (Req 9.8)
     * @param summaryForId builds the summary for the assigned ID; its {@code evidenceId()} must equal it
     */
    public StoredEvidence store(InvestigationContext ctx, EvidenceType type, String source, Instant observedAt,
            String citation, Function<String, ? extends ToolSummary> summaryForId) {
        Objects.requireNonNull(ctx, "ctx");
        return store(ctx.incidentId(), ctx.traceId(), type, source, observedAt, citation, summaryForId);
    }

    /**
     * Stores the Incident's alert as Evidence of type {@code ALERT}, source {@code alert-intake} and
     * {@code observedAt = detectedAt} (Req 9.7). The citation is the first dashboard link, or
     * {@code alert-intake fingerprint <fingerprint>} when there is none.
     */
    public Evidence storeAlert(Incident incident, String traceId) {
        Objects.requireNonNull(incident, "incident");
        String citation = incident.dashboardLinks().isEmpty()
                ? ALERT_SOURCE + " fingerprint " + incident.fingerprint()
                : incident.dashboardLinks().get(0);
        return store(incident.id(), traceId, EvidenceType.ALERT, ALERT_SOURCE, incident.detectedAt(), citation,
                id -> AlertSummary.of(id, incident)).evidence();
    }

    /** References to the Incident's Evidence in ID order; empty if there is none. */
    public List<EvidenceRef> refs(String incidentId) {
        return repository.findByIncident(incidentId).stream()
                .map(e -> new EvidenceRef(e.id(), e.type(), e.source(), e.observedAt(), e.headline(), e.citation()))
                .toList();
    }

    /** Formats an Evidence_ID. */
    public static String evidenceId(String incidentId, int n) {
        return "ev-" + incidentId + "-" + n;
    }

    private StoredEvidence store(String incidentId, String traceId, EvidenceType type, String source,
            Instant observedAt, String citation, Function<String, ? extends ToolSummary> summaryForId) {
        Objects.requireNonNull(incidentId, "incidentId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(summaryForId, "summaryForId");
        Sequence sequence = sequences.computeIfAbsent(incidentId,
                id -> new Sequence(repository.findByIncident(id).size()));
        Evidence evidence;
        RedactionResult redaction;
        sequence.lock.lock();
        try {
            int n = sequence.last.get() + 1;
            String id = evidenceId(incidentId, n);
            ToolSummary summary = Objects.requireNonNull(summaryForId.apply(id), "summary");
            if (!id.equals(summary.evidenceId())) {
                throw new IllegalStateException(
                        "summary evidenceId " + summary.evidenceId() + " does not match assigned " + id);
            }
            LinkedHashMap<String, Object> payload = mapper.convertValue(summary, MAP_TYPE);
            redaction = Objects.requireNonNull(redactor.redact(payload), "redaction result");
            // The headline is not in the payload but is shown in reports and Slack, so it is redacted too.
            String headline = summary.headline();
            if (headline != null) {
                RedactionResult headlineRedaction = Objects.requireNonNull(
                        redactor.redact(Map.of(HEADLINE_KEY, headline)), "headline redaction result");
                headline = (String) headlineRedaction.payload().get(HEADLINE_KEY);
                redaction = merge(redaction, headlineRedaction);
            }
            String capped = ResultCapper.cap(mapper, redaction.payload(), id, maxResultBytes);
            evidence = new Evidence(id, incidentId, type, source, observedAt, capped, headline,
                    redaction.payload(), citation, redaction.categories());
            repository.save(evidence);
            sequence.last.set(n);
        } finally {
            sequence.lock.unlock();
        }
        log.debug("evidence stored evidence_id={} type={} source={} redaction_status={} trace_id={}",
                evidence.id(), type, source, redaction.status(), traceId);
        return new StoredEvidence(evidence, redaction.status());
    }

    /** The payload redaction with the headline's categories added; applied if either redacted something. */
    private static RedactionResult merge(RedactionResult payload, RedactionResult headline) {
        if (headline.categories().isEmpty()) {
            return payload;
        }
        Set<String> categories = new LinkedHashSet<>(payload.categories());
        categories.addAll(headline.categories());
        return new RedactionResult(payload.payload(), categories, RedactionResult.APPLIED);
    }

    /** Last assigned number for one incident, guarded by {@link #lock} for writes. */
    private static final class Sequence {
        final ReentrantLock lock = new ReentrantLock();
        final AtomicInteger last;

        Sequence(int alreadyStored) {
            this.last = new AtomicInteger(alreadyStored);
        }
    }
}
