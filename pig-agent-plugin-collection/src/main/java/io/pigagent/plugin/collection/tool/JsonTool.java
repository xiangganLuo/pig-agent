package io.pigagent.plugin.collection.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.pigagent.tool.contract.ToolErrors;

/**
 * Pure-compute JSON tools built on Jackson (battle-tested, offline): pretty-print and validate. No
 * I/O. {@code jsonPrettyPrint} of malformed input returns the canonical {@code {"error"}};
 * {@code jsonValidate} reports validity as normal output ({@code {"valid":true|false,...}}).
 */
public final class JsonTool {

    private final ObjectMapper mapper = new ObjectMapper();

    @Tool(name = "jsonPrettyPrint",
            description = "Reformat a JSON string with 2-space indentation; invalid JSON returns an error.")
    public String jsonPrettyPrint(
            @ToolParam(name = "json", description = "the JSON text to pretty-print") String json) {
        try {
            Object tree = mapper.readValue(json == null ? "" : json, Object.class);
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree);
        } catch (JsonProcessingException e) {
            return ToolErrors.message("invalid JSON: " + rootReason(e));
        }
    }

    @Tool(name = "jsonValidate",
            description = "Validate a JSON string; returns {\"valid\":true} or {\"valid\":false,\"error\":...}.")
    public String jsonValidate(
            @ToolParam(name = "json", description = "the JSON text to validate") String json) {
        try {
            mapper.readTree(json == null ? "" : json);
            return "{\"valid\":true}";
        } catch (JsonProcessingException e) {
            String reason = ToolErrors.message(rootReason(e));
            // reason is {"error":"..."}; nest the sanitized message under a valid=false envelope
            return "{\"valid\":false,\"error\":" + extractErrorValue(reason) + "}";
        }
    }

    /** The first line of the Jackson message (drops the noisy source/location tail). */
    private static String rootReason(JsonProcessingException e) {
        String msg = e.getOriginalMessage();
        if (msg == null || msg.isBlank()) {
            msg = e.getMessage();
        }
        if (msg == null) {
            return "parse error";
        }
        int nl = msg.indexOf('\n');
        return nl >= 0 ? msg.substring(0, nl) : msg;
    }

    /** Pull the JSON string value out of a {@code {"error":"..."}} envelope (already escaped). */
    private static String extractErrorValue(String errorEnvelope) {
        int colon = errorEnvelope.indexOf(':');
        int end = errorEnvelope.lastIndexOf('}');
        if (colon >= 0 && end > colon) {
            return errorEnvelope.substring(colon + 1, end);
        }
        return "\"parse error\"";
    }
}
