package dev.investigator.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.investigator.domain.ReportDraft;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Report_Draft_Printer: serializes a {@link ReportDraft} to compact JSON with the field names of the
 * Report_Draft_Schema, in record component order (Req 15.4).
 *
 * <p>Every valid draft prints to JSON that conforms to the schema, and {@link ReportDraftParser}
 * reads it back to an equal draft (Req 15.5). The output of a draft that breaks a schema rule, for
 * example a summary over 2000 characters or a null field, is not schema-conformant and is rejected by
 * the parser.
 */
@Component
public final class ReportDraftPrinter {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    public String print(ReportDraft draft) {
        Objects.requireNonNull(draft, "draft");
        try {
            return mapper.writeValueAsString(draft);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot print report draft", e);
        }
    }
}
