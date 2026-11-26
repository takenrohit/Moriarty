package dev.investigator.feedback;

import java.time.Instant;

/**
 * One stored feedback submission on an Incident's report (Req 8.1).
 *
 * @param id generated feedback ID
 * @param incidentId the Incident whose report was rated
 * @param rating {@code helpful} or {@code unhelpful}
 * @param note optional free text, at most {@value FeedbackService#MAX_NOTE} characters
 * @param submittedBy optional submitter name, at most {@value FeedbackService#MAX_SUBMITTER} characters
 */
public record Feedback(long id, String incidentId, Rating rating, String note, String submittedBy,
        Instant createdAt) {

    /** The allowed ratings, stored in the integer {@code feedback.rating} column. */
    public enum Rating {
        UNHELPFUL(0), HELPFUL(1);

        private final int code;

        Rating(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        public static Rating ofCode(int code) {
            for (Rating rating : values()) {
                if (rating.code == code) {
                    return rating;
                }
            }
            throw new IllegalArgumentException("unknown rating code " + code);
        }
    }
}
