package dev.investigator.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Error body of the JSON API: {@code {"errors":[{"field":"severity","message":"..."}]}}.
 *
 * @param errors one entry per problem; never empty
 */
public record ApiErrorResponse(List<FieldMessage> errors) {

    public ApiErrorResponse {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    /** Builds a response with a single error that names no field. */
    public static ApiErrorResponse of(String message) {
        return new ApiErrorResponse(List.of(new FieldMessage(null, message)));
    }

    /** Builds a response with a single error for {@code field}. */
    public static ApiErrorResponse of(String field, String message) {
        return new ApiErrorResponse(List.of(new FieldMessage(field, message)));
    }

    /**
     * One error.
     *
     * @param field the offending request field, or {@code null} when the error is not about one field
     * @param message human-readable description; never contains request body content
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FieldMessage(String field, String message) {
    }
}
