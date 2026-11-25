package dev.investigator.runbook;

import java.util.Objects;

/** A loaded runbook with its score for one service and symptom (Req 12.6). */
public record ScoredRunbook(LoadedRunbook loaded, int score) {

    public ScoredRunbook {
        Objects.requireNonNull(loaded, "loaded");
    }

    public String fileName() {
        return loaded.fileName();
    }

    public Runbook runbook() {
        return loaded.runbook();
    }
}
