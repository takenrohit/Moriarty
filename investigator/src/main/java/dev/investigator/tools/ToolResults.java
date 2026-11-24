package dev.investigator.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Tool_Results that carry no Evidence (Req 10.9, 10.10, 12.7). */
public final class ToolResults {

    /** Result of {@code findRunbook} when no runbook matches. */
    public static final String NOT_FOUND = "{\"found\":false}";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolResults() {
    }

    /** Builds {@code {"error": <error>, "hint": <hint>}} as JSON, escaping both values. */
    public static String error(String error, String hint) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("error", error);
        node.put("hint", hint);
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize error result", e);
        }
    }
}
