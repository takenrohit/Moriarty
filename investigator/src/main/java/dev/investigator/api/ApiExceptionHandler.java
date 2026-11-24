package dev.investigator.api;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import dev.investigator.alert.AlertValidationException;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps API exceptions to JSON error bodies.
 *
 * <ul>
 *   <li>Bean-validation errors: 400 with one entry per field error (Req 4.5, 4.8).
 *   <li>{@link AlertValidationException} from the AlertNormalizer (bad severity or {@code detectedAt}):
 *       400 with one entry per rejected field (Req 4.6, 4.7).
 *   <li>Unreadable body (malformed JSON, missing body, wrong JSON type): 400 with a generic message.
 *       The request body and the parser message are never echoed or logged.
 *   <li>{@link IncidentNotFoundException}: 404 (Req 6.8).
 * </ul>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    static final String UNREADABLE_BODY_MESSAGE = "request body is missing or is not valid JSON";
    static final String WRONG_TYPE_MESSAGE = "has the wrong JSON type";

    private static final Comparator<ApiErrorResponse.FieldMessage> ERROR_ORDER = Comparator
            .comparing(ApiErrorResponse.FieldMessage::field, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(ApiErrorResponse.FieldMessage::message, Comparator.nullsFirst(Comparator.naturalOrder()));

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> invalidArgument(MethodArgumentNotValidException ex) {
        List<ApiErrorResponse.FieldMessage> errors = ex.getBindingResult().getAllErrors().stream()
                .map(error -> new ApiErrorResponse.FieldMessage(
                        error instanceof FieldError fieldError ? fieldError.getField() : null,
                        error.getDefaultMessage()))
                .sorted(ERROR_ORDER)
                .toList();
        return ResponseEntity.badRequest().body(new ApiErrorResponse(errors));
    }

    @ExceptionHandler(AlertValidationException.class)
    ResponseEntity<ApiErrorResponse> invalidAlert(AlertValidationException ex) {
        List<ApiErrorResponse.FieldMessage> errors = ex.violations().stream()
                .map(violation -> new ApiErrorResponse.FieldMessage(violation.field(), violation.message()))
                .sorted(ERROR_ORDER)
                .toList();
        return ResponseEntity.badRequest().body(new ApiErrorResponse(errors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiErrorResponse> unreadableBody(HttpMessageNotReadableException ex) {
        String field = wrongTypeField(ex);
        ApiErrorResponse body = field == null
                ? ApiErrorResponse.of(UNREADABLE_BODY_MESSAGE)
                : ApiErrorResponse.of(field, WRONG_TYPE_MESSAGE);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(IncidentNotFoundException.class)
    ResponseEntity<ApiErrorResponse> incidentNotFound(IncidentNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiErrorResponse.of("incident not found"));
    }

    /**
     * Returns the top-level request field (for example {@code labels}) when the body is valid JSON but a
     * value has the wrong type, otherwise {@code null}. Only the first path element is used: unknown
     * top-level properties are ignored, so it is always a {@link MockAlertRequest} property name. Deeper
     * elements can be label keys taken from the body and are not echoed.
     */
    private static String wrongTypeField(HttpMessageNotReadableException ex) {
        if (!(ex.getCause() instanceof MismatchedInputException mismatch) || mismatch.getPath().isEmpty()) {
            return null;
        }
        JsonMappingException.Reference first = mismatch.getPath().get(0);
        return first.getFieldName();
    }
}
