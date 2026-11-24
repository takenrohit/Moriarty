package dev.investigator.evidence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Serializes a summary to compact JSON of at most {@code maxBytes} UTF-8 bytes (Req 9.6, NFR-31).
 *
 * <p>Algorithm (design "Evidence"): serialize; if over the cap, truncate every string value longer
 * than {@value #MAX_STRING_CODE_POINTS} code points to that length (ending in {@code …}); then
 * repeatedly remove the last element of the longest array (ties go to the first array in document
 * order); if still over, return {@code {"evidenceId": "<id>", "truncated": true, "note": "summary
 * exceeded size cap"}}. Truncation is by code point, so surrogate pairs are never split, and the
 * result is always valid JSON.
 */
public final class ResultCapper {

    /** Code-point length that string values are truncated to once a summary is over the cap. */
    public static final int MAX_STRING_CODE_POINTS = 200;

    static final String FALLBACK_NOTE = "summary exceeded size cap";

    private ResultCapper() {
    }

    /**
     * @param summary any value Jackson can serialize, typically a summary record or its map payload
     * @param evidenceId the ID written into the fallback result; may be null
     * @param maxBytes the cap in UTF-8 bytes
     * @throws IllegalArgumentException if even the fallback result does not fit in {@code maxBytes}
     */
    public static String cap(ObjectMapper mapper, Object summary, String evidenceId, int maxBytes) {
        ObjectWriter writer = mapper.writer().without(SerializationFeature.INDENT_OUTPUT);
        JsonNode root = summary == null ? NullNode.getInstance() : mapper.valueToTree(summary);
        byte[] bytes = write(writer, root);
        if (bytes.length <= maxBytes) {
            return utf8(bytes);
        }

        root = truncateStrings(root);
        bytes = write(writer, root);
        if (bytes.length <= maxBytes) {
            return utf8(bytes);
        }

        long size = bytes.length;
        List<ArrayNode> arrays = collectArrays(root, new ArrayList<>());
        ArrayNode longest;
        while ((longest = longest(arrays)) != null) {
            JsonNode removed = longest.remove(longest.size() - 1);
            // "[a,b]" -> "[a]" removes ",b"; "[a]" -> "[]" removes "a".
            size -= write(writer, removed).length + (longest.isEmpty() ? 0 : 1);
            if (removed.isContainerNode()) {
                arrays = collectArrays(root, new ArrayList<>());
            }
            if (size <= maxBytes) {
                bytes = write(writer, root);
                if (bytes.length <= maxBytes) {
                    return utf8(bytes);
                }
                size = bytes.length;
            }
        }

        ObjectNode fallback = mapper.createObjectNode();
        fallback.put("evidenceId", evidenceId);
        fallback.put("truncated", true);
        fallback.put("note", FALLBACK_NOTE);
        bytes = write(writer, fallback);
        if (bytes.length > maxBytes) {
            throw new IllegalArgumentException(
                    "maxBytes " + maxBytes + " is too small for the fallback result of " + evidenceId);
        }
        return utf8(bytes);
    }

    /** Replaces long string values in place; returns the (possibly replaced) root. */
    private static JsonNode truncateStrings(JsonNode node) {
        if (node.isTextual()) {
            String text = node.textValue();
            String cut = Headlines.truncate(text, MAX_STRING_CODE_POINTS);
            return cut.equals(text) ? node : TextNode.valueOf(cut);
        }
        if (node instanceof ObjectNode object) {
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                JsonNode child = object.get(name);
                JsonNode replaced = truncateStrings(child);
                if (replaced != child) {
                    object.set(name, replaced);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                JsonNode child = array.get(i);
                JsonNode replaced = truncateStrings(child);
                if (replaced != child) {
                    array.set(i, replaced);
                }
            }
        }
        return node;
    }

    /** Arrays in depth-first document order, outer arrays before the arrays they contain. */
    private static List<ArrayNode> collectArrays(JsonNode node, List<ArrayNode> out) {
        if (node instanceof ArrayNode array) {
            out.add(array);
        }
        if (node.isContainerNode()) {
            for (JsonNode child : node) {
                collectArrays(child, out);
            }
        }
        return out;
    }

    private static ArrayNode longest(List<ArrayNode> arrays) {
        ArrayNode best = null;
        for (ArrayNode array : arrays) {
            if (!array.isEmpty() && (best == null || array.size() > best.size())) {
                best = array;
            }
        }
        return best;
    }

    private static byte[] write(ObjectWriter writer, JsonNode node) {
        try {
            return writer.writeValueAsBytes(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize summary", e);
        }
    }

    private static String utf8(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
