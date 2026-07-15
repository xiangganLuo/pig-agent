package io.pigagent.channel.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Pure mapping + signing for the Feishu/Lark (飞书) custom robot and event subscription. No I/O — it
 * operates on raw request bodies / headers so it is fully unit-testable offline.
 *
 * <p><b>Inbound event subscription:</b> {@link #challenge} echoes a {@code url_verification} handshake;
 * {@link #extractMessage} reads the text of an {@code im.message.receive_v1} event (the {@code text}
 * inside the nested-JSON {@code event.message.content}); {@link #verifySignature} verifies the
 * {@code X-Lark-Signature} — {@code hex(SHA-256(timestamp + nonce + encrypt + token))} — in constant
 * time. <b>Outbound custom bot:</b> {@link #sign} is {@code base64(HMAC-SHA256(key=(timestamp + "\n" +
 * secret), data=empty))} (note the key/data placement differs from DingTalk — exactly the per-platform
 * variation the Strategy isolates), carried in the body alongside the
 * {@code {"msg_type":"text","content":{"text":...}}} payload.
 *
 * <p>Encrypted-push (AES) events are out of scope: only plaintext event bodies are parsed (the
 * {@code encrypt} field, if present, still participates in the signature).
 */
public final class FeishuCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String HMAC_ALGO = "HmacSHA256";
    private static final String SHA_256 = "SHA-256";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private FeishuCodec() {
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
     * Extract the text of an {@code im.message.receive_v1} event: {@code header.event_type} must match
     * and {@code event.message.message_type} be {@code text}; the message text lives inside the
     * nested-JSON {@code event.message.content} ({@code {"text":"..."}}).
     */
    public static Optional<String> extractMessage(String body) {
        JsonNode root = parse(body);
        if (root == null) {
            return Optional.empty();
        }
        JsonNode header = root.get("header");
        if (header == null || !"im.message.receive_v1".equals(text(header, "event_type"))) {
            return Optional.empty();
        }
        JsonNode message = root.path("event").path("message");
        if (message.isMissingNode() || !"text".equals(text(message, "message_type"))) {
            return Optional.empty();
        }
        JsonNode content = message.get("content");
        if (content == null || !content.isTextual()) {
            return Optional.empty();
        }
        JsonNode inner = parse(content.asText());
        String messageText = inner == null ? null : text(inner, "text");
        return (messageText == null || messageText.isBlank()) ? Optional.empty() : Optional.of(messageText.strip());
    }

    /** The top-level {@code encrypt} field (empty string when absent / plaintext mode). */
    public static String encryptField(String body) {
        JsonNode root = parse(body);
        String encrypt = root == null ? null : text(root, "encrypt");
        return encrypt == null ? "" : encrypt;
    }

    /** Event-subscription signature: {@code hex(SHA-256(timestamp + nonce + encrypt + token))}. */
    public static String signature(String timestamp, String nonce, String encrypt, String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA_256);
            byte[] raw = digest.digest((timestamp + nonce + encrypt + token).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                hex.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Constant-time verification of an {@code X-Lark-Signature}. Any null input yields false. */
    public static boolean verifySignature(String token, String timestamp, String nonce, String encrypt,
                                          String providedSignature) {
        if (token == null || timestamp == null || nonce == null || encrypt == null || providedSignature == null) {
            return false;
        }
        String expected = signature(timestamp, nonce, encrypt, token);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                providedSignature.getBytes(StandardCharsets.UTF_8));
    }

    /** Outbound custom-bot sign: {@code base64(HMAC-SHA256(key=(timestamp + "\n" + secret), data=empty))}. */
    public static String sign(String timestamp, String secret) {
        try {
            String stringToSign = timestamp + "\n" + secret;
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(stringToSign.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] raw = mac.doFinal(new byte[0]);
            return Base64.getEncoder().encodeToString(raw);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /** Build the {@code url_verification} response body: {@code {"challenge":"..."}} (properly escaped). */
    public static String buildChallengeResponse(String challenge) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("challenge", challenge == null ? "" : challenge);
            return MAPPER.writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{\"challenge\":\"\"}";
        }
    }

    /** Build the unsigned text message body: {@code {"msg_type":"text","content":{"text":...}}}. */
    public static String buildTextPayload(String content) {
        return writePayload(content, null, null);
    }

    /** Build the signed text message body, prepending {@code timestamp}/{@code sign}. */
    public static String buildSignedTextPayload(String content, String timestamp, String sign) {
        return writePayload(content, timestamp, sign);
    }

    private static String writePayload(String content, String timestamp, String sign) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            if (timestamp != null && sign != null) {
                root.put("timestamp", timestamp);
                root.put("sign", sign);
            }
            root.put("msg_type", "text");
            ObjectNode inner = root.putObject("content");
            inner.put("text", content == null ? "" : content);
            return MAPPER.writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{\"msg_type\":\"text\",\"content\":{\"text\":\"\"}}";
        }
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
