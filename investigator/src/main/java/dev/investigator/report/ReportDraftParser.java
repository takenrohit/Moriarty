package dev.investigator.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.investigator.domain.ReportDraft;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Report_Draft_Parser: turns the model's raw answer into a {@link ReportDraft} (design "Report pipeline").
 *
 * <p>This first version deserializes with a strict Jackson mapper only: unknown fields, missing or
 * null fields, numbers for enums, scalar coercion (for example {@code "true"} for a boolean) and
 * trailing content are all rejected, and each error names the JSON path. Fence stripping, JSON Schema
 * validation and the timeline {@code Instant} check are added in task 17.1.
 */
@Component
public final class ReportDraftParser {

    static final String EMPTY_DRAFT = "report draft is empty";

    private final ObjectMapper strict = strictMapper();

    /** Parses {@code raw}; never throws for bad input. */
    public ParseResult parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ParseResult.Invalid(List.of(EMPTY_DRAFT));
        }
        try {
            ReportDraft draft = strict.readValue(raw, ReportDraft.class);
            if (draft == null) {
                return new ParseResult.Invalid(List.of(EMPTY_DRAFT));
            }
            return new ParseResult.Ok(draft);
        } catch (JsonProcessingException e) {
            return new ParseResult.Invalid(List.of(describe(e)));
        }
    }

    /** The strict mapper for model drafts (design "Configuration": separate from the HTTP JSON_Codec). */
    static ObjectMapper strictMapper() {
        return JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
                .build();
    }

    private static String describe(JsonProcessingException e) {
        String message = e.getOriginalMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }
        if (e instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()) {
            return path(mapping.getPath()) + ": " + message;
        }
        return "$: " + message;
    }

    private static String path(List<JsonMappingException.Reference> references) {
        StringBuilder path = new StringBuilder("$");
        for (JsonMappingException.Reference reference : references) {
            if (reference.getFieldName() != null) {
                path.append('.').append(reference.getFieldName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.toString();
    }
}
