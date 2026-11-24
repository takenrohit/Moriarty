package dev.investigator.report;

import dev.investigator.domain.ReportDraft;
import java.util.List;
import java.util.Objects;

/** Outcome of {@link ReportDraftParser#parse(String)}: a parsed draft or the reasons it was rejected. */
public sealed interface ParseResult {

    /** The draft was parsed. */
    record Ok(ReportDraft draft) implements ParseResult {

        public Ok {
            Objects.requireNonNull(draft, "draft");
        }
    }

    /** The draft was rejected; {@code errors} is non-empty. */
    record Invalid(List<String> errors) implements ParseResult {

        public Invalid {
            errors = List.copyOf(errors);
            if (errors.isEmpty()) {
                throw new IllegalArgumentException("an invalid result needs at least one error");
            }
        }
    }
}
