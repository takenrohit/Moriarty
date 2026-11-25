package dev.investigator.evidence;

import com.fasterxml.jackson.annotation.JsonIgnore;
import dev.investigator.integration.changes.ChangeRecord;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Tool_Result of {@code getRecentChanges} (Req 10.6). The tool stores one Evidence per change, so
 * this record has no Evidence_ID of its own; each {@link Change} carries one.
 *
 * @param lookbackHours the applied look-back, at most 24
 * @param appliedStart start of the applied look-back range ({@code appliedEnd − lookbackHours})
 * @param appliedEnd end of the applied look-back range (the Investigation_Window end)
 * @param clamped true when the requested look-back was larger than the maximum (Req 11.8)
 */
public record ChangesSummary(String service, int lookbackHours, Instant appliedStart, Instant appliedEnd,
        boolean clamped, List<Change> changes) {

    public ChangesSummary {
        changes = changes == null ? List.of() : List.copyOf(changes);
    }

    /** One deployment, stored as one Evidence of type {@code DEPLOYMENT}. */
    public record Change(
            String evidenceId,
            String version,
            Instant deployedAt,
            String author,
            String prTitle,
            String link,
            List<String> changedFiles,
            @JsonIgnore String headline) implements ToolSummary {

        public Change {
            changedFiles = changedFiles == null ? List.of() : List.copyOf(changedFiles);
            headline = Headlines.normalize(headline);
        }

        /**
         * Builds the summary of one change with the headline {@code <service> <version> deployed by
         * <author>} and at most {@code maxChangedFiles} file paths.
         */
        public static Change from(String evidenceId, ChangeRecord record, int maxChangedFiles) {
            Objects.requireNonNull(record, "record");
            List<String> files = record.changedFiles();
            List<String> kept = files.subList(0, Math.min(files.size(), Math.max(0, maxChangedFiles)));
            return new Change(evidenceId, record.version(), record.deployedAt(), record.author(),
                    record.prTitle(), record.link(), kept,
                    record.service() + " " + record.version() + " deployed by " + record.author());
        }
    }
}
