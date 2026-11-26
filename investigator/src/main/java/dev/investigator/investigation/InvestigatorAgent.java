package dev.investigator.investigation;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.memory.ChatMemoryAccess;

/**
 * The Investigator_Agent: a LangChain4j AI service that plans the investigation, calls the
 * Observability_Tools and returns the Report_Draft (Req 13.1).
 *
 * <p>The method returns the draft as the raw JSON string from the model (CP-4); the report parser
 * and validator check it in Java (Req 13.5). Chat memory is keyed by Incident_ID; the orchestrator
 * evicts it through {@link ChatMemoryAccess#evictChatMemory(Object)} when the investigation ends.
 * One memory ID must not be used by two calls at the same time, which holds because each incident
 * has one investigation.
 */
public interface InvestigatorAgent extends ChatMemoryAccess {

    /**
     * Runs the tool loop for one incident and returns the model's final answer.
     *
     * @param incidentId Incident_ID, used as the chat memory ID and passed to every tool as its
     *     {@code @ToolMemoryId}
     * @param incidentJson the Incident serialized with the JSON_Codec ({@link PromptInputs})
     * @param windowStart Investigation_Window start as an ISO-8601 instant
     * @param windowEnd Investigation_Window end as an ISO-8601 instant
     * @param alertEvidenceId Evidence_ID of the incident's {@code ALERT} Evidence
     * @param reportSchema the Report_Draft_Schema text ({@link PromptInputs#reportSchema()})
     * @return the Report_Draft as a JSON string, not yet validated
     */
    @SystemMessage(fromResource = "prompts/investigator-system.txt")
    @UserMessage(fromResource = "prompts/investigate-incident.txt")
    String investigate(
            @MemoryId String incidentId,
            @V("incident") String incidentJson,
            @V("windowStart") String windowStart,
            @V("windowEnd") String windowEnd,
            @V("alertEvidenceId") String alertEvidenceId,
            @V("reportSchema") String reportSchema);

    /**
     * Asks the model once to correct a draft that failed parsing or validation (Req 4.3). Uses the same
     * chat memory as {@link #investigate}, so the model sees its previous draft and the tool results;
     * the tools stay available. The system message is the same as {@link #investigate}'s, which the
     * memory already holds, so it is not added again.
     *
     * @param incidentId Incident_ID, the chat memory ID of the investigation
     * @param errors the validation errors, one per line
     * @return the corrected Report_Draft as a JSON string, not yet validated
     */
    @SystemMessage(fromResource = "prompts/investigator-system.txt")
    @UserMessage(fromResource = "prompts/correct-report.txt")
    String correct(@MemoryId String incidentId, @V("errors") String errors);
}
