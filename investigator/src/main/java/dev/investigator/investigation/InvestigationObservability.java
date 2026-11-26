package dev.investigator.investigation;

import dev.investigator.domain.Incident;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Creates the investigator's observations, which Micrometer Tracing turns into spans (Req 1, CP-69):
 * {@value #INVESTIGATION} per investigation, with {@value #TOOL_PREFIX}{@code <name>} and
 * {@value #LLM_CHAT} children.
 *
 * <p>Children take the current observation of the calling thread as parent. The orchestrator opens
 * the investigation observation's scope on the run thread and on each agent thread
 * ({@link #openScope}), so the tool and LLM observations created on the agent thread nest under it.
 * Low-cardinality keys (meter tags and span attributes) always have a value, so every meter of one name
 * has the same tag keys; {@code incident.id} and {@code tokens} are high-cardinality (span attributes
 * only).
 */
@Component
public class InvestigationObservability {

    public static final String INVESTIGATION = "investigation";
    public static final String TOOL_PREFIX = "tool.";
    public static final String LLM_CHAT = "llm.chat";

    public static final String INCIDENT_ID = "incident.id";
    public static final String SERVICE = "service";
    public static final String SEVERITY = "severity";
    public static final String STATUS = "status";
    public static final String MODEL = "model";
    public static final String TOKENS = "tokens";

    /** Value of a low-cardinality key that is not known (yet). */
    public static final String UNKNOWN = "unknown";

    private final ObservationRegistry registry;

    public InvestigationObservability(ObservationRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** The not-yet-started {@value #INVESTIGATION} observation; {@code incident} may be null. */
    public Observation investigation(String incidentId, Incident incident) {
        return Observation.createNotStarted(INVESTIGATION, registry)
                .highCardinalityKeyValue(INCIDENT_ID, orUnknown(incidentId))
                .lowCardinalityKeyValue(SERVICE, orUnknown(incident == null ? null : incident.affectedService()))
                .lowCardinalityKeyValue(SEVERITY,
                        orUnknown(incident == null || incident.severity() == null ? null : incident.severity().name()))
                .lowCardinalityKeyValue(STATUS, UNKNOWN);
    }

    /** The not-yet-started {@value #TOOL_PREFIX}{@code <toolName>} observation, child of the current one. */
    public Observation tool(String toolName) {
        return Observation.createNotStarted(TOOL_PREFIX + toolName, registry).lowCardinalityKeyValue(STATUS, UNKNOWN);
    }

    /** The not-yet-started {@value #LLM_CHAT} observation, child of the current one. */
    public Observation llmChat(String model) {
        return Observation.createNotStarted(LLM_CHAT, registry)
                .lowCardinalityKeyValue(MODEL, orUnknown(model))
                .lowCardinalityKeyValue(STATUS, UNKNOWN);
    }

    /** The observation in scope on the calling thread, or null. */
    public Observation current() {
        return registry.getCurrentObservation();
    }

    /** Opens {@code observation}'s scope on the calling thread; null if {@code observation} is null. */
    public static Observation.Scope openScope(Observation observation) {
        return observation == null ? null : observation.openScope();
    }

    static String orUnknown(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value;
    }
}
