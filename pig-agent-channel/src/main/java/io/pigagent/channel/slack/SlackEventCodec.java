package io.pigagent.channel.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Pure mapping + signature verification for the Slack Events API. No I/O — everything here operates
 * on the raw request body / headers so it is fully unit-testable offline.
 *
 * <p>Handles the {@code url_verification} handshake (echo the {@code challenge}), extracts the text
 * of a genuine {@code message} event (ignoring bot echoes / {@code subtype} messages to avoid
 * self-triggering loops), and verifies the Slack {@code v0} request signature
 * ({@code v0={hex(HMAC-SHA256(signingSecret, "v0:" + timestamp + ":" + body))}}).
 */
public final class SlackEventCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String HMAC_ALGO = "HmacSHA256";
    private static final String SIG_PREFIX = "v0=";

    private SlackEventCodec() {
    }

    /** If the body is a {@code url_verification} request, return its {@code challenge}. */
    public static Optional<String> challenge(String body) {
        JsonNode root = parse(body);
        if (root != null && "url_verification".equals(text(root, "type"))) {
            String challenge = text(root, "challenge");
            if (challenge != null) {
                return Optional.of(challenge);
            }
        }
        return Optional.empty();
    }

    /**
     * Extract the text of a genuine inbound message event. Empty unless the body is an
     * {@code event_callback} whose {@code event.type} is {@code message}, with no {@code bot_id}
     * and no {@code subtype} (bot echoes / edits are ignored to prevent self-triggering).
     */
    public static Optional<String> extractText(String body) {
        JsonNode root = parse(body);
        if (root == null || !"event_callback".equals(text(root, "type"))) {
            return Optional.empty();
        }
        JsonNode event = root.get("event");
        if (event == null || !event.isObject() || !"message".equals(text(event, "type"))) {
            return Optional.empty();
        }
        if (event.hasNonNull("bot_id") || event.hasNonNull("subtype")) {
            return Optional.empty();
        }
        String messageText = text(event, "text");
        return (messageText == null || messageText.isBlank()) ? Optional.empty() : Optional.of(messageText);
    }

    /** Compute the Slack {@code v0=} request signature for the given secret / timestamp / body. */
    public static String sign(String signingSecret, String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] raw = mac.doFinal(("v0:" + timestamp + ":" + body).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(SIG_PREFIX);
            for (byte b : raw) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /** Constant-time verification of a provided Slack signature. Any null input yields false. */
    public static boolean verifySignature(String signingSecret, String timestamp, String body,
                                          String providedSignature) {
        if (signingSecret == null || timestamp == null || body == null || providedSignature == null) {
            return false;
        }
        String expected = sign(signingSecret, timestamp, body);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                providedSignature.getBytes(StandardCharsets.UTF_8));
    }

    private static JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }
}
