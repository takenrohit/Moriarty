package dev.investigator.evidence;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

/**
 * Tool_Result of {@code findRunbook} when a runbook matches (Req 10.7). The not-found result
 * {@code {"found": false}} is not Evidence and has no record.
 */
public record RunbookSummary(
        String evidenceId,
        String title,
        String service,
        List<String> steps,
        @JsonIgnore String headline) implements ToolSummary {

    public RunbookSummary {
        steps = steps == null ? List.of() : List.copyOf(steps);
        headline = Headlines.normalize(headline);
    }

    /** Builds the summary with the headline {@code Runbook "<title>" for <service>}. */
    public static RunbookSummary of(String evidenceId, String title, String service, List<String> steps) {
        return new RunbookSummary(evidenceId, title, service, steps,
                "Runbook \"" + title + "\" for " + service);
    }
}
