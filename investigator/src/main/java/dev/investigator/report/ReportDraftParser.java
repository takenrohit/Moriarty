package dev.investigator.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.PathType;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import dev.investigator.domain.DraftTimelineEntry;
import dev.investigator.domain.ReportDraft;
import dev.investigator.investigation.PromptInputs;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Report_Draft_Parser: turns the model's raw answer into a {@link ReportDraft} (design "Report pipeline",
 * Req 15.1 to 15.3, 15.9 to 15.11).
 *
 * <p>Steps, each of which stops at the first failing step and returns every error that step found:
 *
 * <ol>
 *   <li>Fence stripping: if the trimmed text starts with {@code ```} (optionally followed by
 *       {@code json}) and ends with {@code ```}, the inner text is kept; anything else is unchanged.
 *   <li>JSON parsing with the strict mapper (duplicate keys and trailing content are rejected).
 *   <li>Validation against the Report_Draft_Schema ({@value PromptInputs#REPORT_SCHEMA_RESOURCE},
 *       draft 2020-12). Each error starts with the JSON path of the offending value; an unknown field
 *       is named by its own path, for example {@code $.timeline[0].extra} (Req 15.2).
 *   <li>Strict Jackson deserialization (unknown fields, missing or null fields, numbers for enums,
 *       scalar coercion are rejected); after schema validation this rarely fails, for example for an
 *       {@code order} beyond the {@code int} range.
 *   <li>{@link Instant#parse} for every timeline {@code at}, because JSON Schema {@code format} is an
 *       annotation only (Req 15.10).
 * </ol>
 *
 * <p>Schema and time errors name the path and the rule, not the value (unknown field names are the
 * exception); JSON syntax errors keep Jackson's message, which may quote the offending token. Semantic
 * checks (incident ID, citations) are done by {@link ReportValidator}.
 */
@Component
public final class ReportDraftParser {

    static final String EMPTY_DRAFT = "report draft is empty";

    private static final String FENCE = "```";

    private final ObjectMapper strict = strictMapper();
    private final JsonSchema schema = loadSchema(strict);

    /** Parses {@code raw}; never throws for bad input. */
    public ParseResult parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ParseResult.Invalid(List.of(EMPTY_DRAFT));
        }
        String text = stripFence(raw);
        if (text.isBlank()) {
            return new ParseResult.Invalid(List.of(EMPTY_DRAFT));
        }

        JsonNode tree;
        try {
            tree = strict.readTree(text);
        } catch (JsonProcessingException e) {
            return new ParseResult.Invalid(List.of(describe(e)));
        }
        if (tree == null || tree.isMissingNode()) {
            return new ParseResult.Invalid(List.of(EMPTY_DRAFT));
        }

        List<String> schemaErrors = schemaErrors(tree);
        if (!schemaErrors.isEmpty()) {
            return new ParseResult.Invalid(schemaErrors);
        }

        ReportDraft draft;
        try {
            draft = strict.treeToValue(tree, ReportDraft.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return new ParseResult.Invalid(List.of(e instanceof JsonProcessingException j ? describe(j)
                    : "$: " + e.getClass().getSimpleName()));
        }
        if (draft == null) {
            return new ParseResult.Invalid(List.of(EMPTY_DRAFT));
        }

        List<String> timeErrors = timelineTimeErrors(draft);
        if (!timeErrors.isEmpty()) {
            return new ParseResult.Invalid(timeErrors);
        }
        return new ParseResult.Ok(draft);
    }

    /**
     * Removes one surrounding Markdown code fence ({@code ```} or {@code ```json}) from the trimmed
     * text; returns {@code raw} unchanged if it is not fenced (Req 15.3).
     */
    static String stripFence(String raw) {
        String trimmed = raw.strip();
        if (trimmed.length() < 2 * FENCE.length() || !trimmed.startsWith(FENCE) || !trimmed.endsWith(FENCE)) {
            return raw;
        }
        String inner = trimmed.substring(FENCE.length(), trimmed.length() - FENCE.length());
        if (inner.regionMatches(true, 0, "json", 0, 4)) {
            inner = inner.substring(4);
        }
        return inner;
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

    private static JsonSchema loadSchema(ObjectMapper mapper) {
        String resource = PromptInputs.REPORT_SCHEMA_RESOURCE;
        try (InputStream in = ReportDraftParser.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource " + resource);
            }
            JsonNode node = mapper.readTree(in);
            SchemaValidatorsConfig config = SchemaValidatorsConfig.builder()
                    .pathType(PathType.JSON_PATH)
                    .locale(Locale.ENGLISH)
                    .formatAssertionsEnabled(false)
                    .build();
            JsonSchema loaded = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(node, config);
            loaded.initializeValidators();
            return loaded;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + resource, e);
        }
    }

    /** Schema errors as {@code <json path>: <message>}, sorted and without duplicates. */
    private List<String> schemaErrors(JsonNode tree) {
        return schema.validate(tree).stream()
                .map(ReportDraftParser::schemaMessage)
                .distinct()
                .sorted()
                .toList();
    }

    private static String schemaMessage(ValidationMessage message) {
        if ("additionalProperties".equals(message.getType()) && message.getProperty() != null) {
            return message.getInstanceLocation().append(message.getProperty())
                    + ": is not defined in the schema (additional properties are not allowed)";
        }
        String location = String.valueOf(message.getInstanceLocation());
        String text = message.getMessage();
        if (text == null || text.isBlank()) {
            text = message.getType();
        }
        return text.startsWith(location) ? text : location + ": " + text;
    }

    private static List<String> timelineTimeErrors(ReportDraft draft) {
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < draft.timeline().size(); i++) {
            DraftTimelineEntry entry = draft.timeline().get(i);
            String path = "$.timeline[" + i + "].at";
            if (entry.at() == null) {
                errors.add(path + ": is required");
                continue;
            }
            try {
                Instant.parse(entry.at());
            } catch (DateTimeParseException e) {
                errors.add(path + ": is not an ISO-8601 instant, for example 2026-11-23T14:38:00Z");
            }
        }
        return errors;
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
