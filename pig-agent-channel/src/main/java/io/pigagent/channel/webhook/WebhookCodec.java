package io.pigagent.channel.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Pure, side-effect-free mapping between the webhook HTTP body and the agent message text.
 *
 * <p>Inbound: a JSON object body is preferred and its {@code message} (else {@code text}) string
 * field is used; a non-JSON body (or one lacking those keys) falls back to the whole trimmed body.
 * Outbound: the agent reply is wrapped as {@code {"reply": "..."}} with proper JSON escaping.
 */
public final class WebhookCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WebhookCodec() {
    }

    /** Extract the message text from a request body; blank/absent input yields an empty string. */
    public static String extractMessage(String body, String contentType) {
        if (body == null) {
            return "";
        }
        String trimmed = body.strip();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (trimmed.startsWith("{")) {
            try {
                JsonNode node = MAPPER.readTree(trimmed);
                if (node != null && node.isObject()) {
                    JsonNode message = node.get("message");
                    if (message != null && message.isTextual()) {
                        return message.asText();
                    }
                    JsonNode text = node.get("text");
                    if (text != null && text.isTextual()) {
                        return text.asText();
                    }
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
                // malformed JSON — fall back to treating the body as plain text
            }
        }
        return trimmed;
    }

    /** Format an agent reply as a JSON response body ({@code {"reply": "..."}}). */
    public static String formatReply(String reply) {
        try {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("reply", reply == null ? "" : reply);
            return MAPPER.writeValueAsString(node);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{\"reply\":\"\"}";
        }
    }
}
