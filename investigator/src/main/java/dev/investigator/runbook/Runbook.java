package dev.investigator.runbook;

import java.util.List;
import java.util.Objects;

/**
 * A runbook: YAML front-matter fields and the Markdown body (Req 12.2).
 *
 * @param symptoms symptom phrases used for retrieval; never null
 * @param tags free-form tags used for retrieval; never null
 * @param body the Markdown after the closing {@code ---} line, verbatim; never null
 */
public record Runbook(String title, String service, List<String> symptoms, List<String> tags, String body) {

    public Runbook {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(service, "service");
        symptoms = symptoms == null ? List.of() : List.copyOf(symptoms);
        tags = tags == null ? List.of() : List.copyOf(tags);
        body = body == null ? "" : body;
    }
}
