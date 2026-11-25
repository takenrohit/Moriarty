package dev.investigator.runbook;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;

/**
 * Runbook_Parser (Req 12.2, 12.3, design "Runbooks").
 *
 * <p>Format: the first line is exactly {@code ---}; YAML front-matter follows until the next line that
 * is exactly {@code ---}; the rest of the text is the body, kept verbatim. CRLF is normalized to LF
 * first, and a leading byte-order mark is dropped. The front-matter is parsed with a SnakeYAML
 * {@link SafeConstructor}, so it can only produce plain maps, lists and scalars. {@code title} and
 * {@code service} are required non-blank scalars; {@code symptoms} and {@code tags} are optional lists
 * of scalars. Other keys are ignored.
 *
 * <p>Every failure is a {@link RunbookFormatException}; no other exception type escapes.
 */
public final class RunbookParser {

    static final String DELIMITER = "---";

    /**
     * @throws RunbookFormatException if the text is null, does not start with a {@code ---} line, has no
     *     closing {@code ---} line, has front-matter that is not a YAML mapping, or lacks a field
     */
    public Runbook parse(String text) {
        if (text == null) {
            throw new RunbookFormatException("runbook text is missing");
        }
        String normalized = text.replace("\r\n", "\n");
        if (normalized.startsWith("\uFEFF")) {
            normalized = normalized.substring(1);
        }
        if (!normalized.startsWith(DELIMITER + "\n")) {
            throw new RunbookFormatException("front-matter is missing: the first line must be ---");
        }
        int frontMatterStart = DELIMITER.length() + 1;
        int lineStart = frontMatterStart;
        while (true) {
            int newline = normalized.indexOf('\n', lineStart);
            String line = newline < 0 ? normalized.substring(lineStart) : normalized.substring(lineStart, newline);
            if (line.equals(DELIMITER)) {
                String yaml = normalized.substring(frontMatterStart, lineStart);
                String body = newline < 0 ? "" : normalized.substring(newline + 1);
                return toRunbook(loadMapping(yaml), body);
            }
            if (newline < 0) {
                throw new RunbookFormatException("front-matter is not closed by a --- line");
            }
            lineStart = newline + 1;
        }
    }

    private static Map<?, ?> loadMapping(String yaml) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(options)).load(yaml);
        } catch (MarkedYAMLException e) {
            Mark mark = e.getProblemMark();
            String where = mark == null ? "" : " at front-matter line " + (mark.getLine() + 1);
            throw new RunbookFormatException("front-matter is not valid YAML" + where, e);
        } catch (RuntimeException e) {
            throw new RunbookFormatException("front-matter is not valid YAML", e);
        }
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new RunbookFormatException("front-matter is not a YAML mapping");
        }
        return map;
    }

    private static Runbook toRunbook(Map<?, ?> map, String body) {
        String title = requiredScalar(map, "title");
        String service = requiredScalar(map, "service");
        return new Runbook(title, service, optionalList(map, "symptoms"), optionalList(map, "tags"), body);
    }

    private static String requiredScalar(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new RunbookFormatException("front-matter lacks " + key);
        }
        String text = scalar(value, key);
        if (text.isBlank()) {
            throw new RunbookFormatException("front-matter has a blank " + key);
        }
        return text;
    }

    private static List<String> optionalList(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> items)) {
            throw new RunbookFormatException(key + " must be a list");
        }
        List<String> out = new ArrayList<>(items.size());
        for (Object item : items) {
            if (item == null) {
                throw new RunbookFormatException(key + " contains an empty item");
            }
            out.add(scalar(item, key + " item"));
        }
        return out;
    }

    private static String scalar(Object value, String what) {
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        throw new RunbookFormatException(what + " must be a text value");
    }
}
