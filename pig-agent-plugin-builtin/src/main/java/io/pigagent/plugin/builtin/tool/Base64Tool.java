package io.pigagent.plugin.builtin.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.pigagent.tool.contract.ToolErrors;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Pure-compute Base64 encode/decode (UTF-8). No I/O. Failures return the canonical {@code {"error"}}. */
public final class Base64Tool {

    @Tool(name = "base64Encode", description = "Base64-encode UTF-8 text (standard alphabet, with padding).")
    public String base64Encode(
            @ToolParam(name = "text", description = "plain text to encode") String text) {
        String value = text == null ? "" : text;
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    @Tool(name = "base64Decode",
            description = "Decode standard Base64 back to UTF-8 text; invalid input returns an error.")
    public String base64Decode(
            @ToolParam(name = "text", description = "Base64 text to decode") String text) {
        String value = text == null ? "" : text;
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return ToolErrors.message("invalid Base64 input");
        }
    }
}
