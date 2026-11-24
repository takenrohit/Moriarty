package dev.investigator.domain;

import java.util.List;

/**
 * Timeline entry in a {@link ReportDraft}.
 *
 * @param at ISO-8601 instant as written by the model; parsed by Java during assembly
 */
public record DraftTimelineEntry(String at, String description, List<String> evidenceIds) {

    public DraftTimelineEntry {
        evidenceIds = Copies.list(evidenceIds);
    }
}
