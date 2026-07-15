package io.pigagent.channel.dingtalk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Pure, side-effect-free signing + message mapping for the DingTalk (钉钉) custom robot. No I/O — it
 * operates on raw strings so it is fully unit-testable offline.
 *
 * <p><b>Signature</b> (used for both outbound URL signing and inbound {@code sign}-header
 * verification, per the same scheme): {@code sign = base64(HMAC-SHA256(key=secret, data=timestamp +
 * "\n" + secret))}. <b>Inbound</b> parsing reads {@code text.content} from an outgoing-robot POST
 * body. <b>Outbound</b> builds the {@code {"msgtype":"text","text":{"content":...}}} payload and,
 * when a secret is set, appends {@code &timestamp=..&sign=..} to the webhook URL.
 */
public final class DingTalkCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String HMAC_ALGO = "HmacSHA256";

    private DingTalkCodec() {
    }

    /** {@code base64(HMAC-SHA256(key=secret, data=timestamp + "\n" + secret))}. */
    public static String sign(String timestamp, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
            byte[] raw = mac.doFinal((timestamp + "\n" + secret).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(raw);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /** Constant-time verification of an inbound {@code sign} header. Any null input yields false. */
    public static boolean verify(String timestamp, String providedSign, String secret) {
        if (timestamp == null || providedSign == null || secret == null) {
            return false;
        }
        String expected = sign(timestamp, secret);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                providedSign.getBytes(StandardCharsets.UTF_8));
    }

    /** Append {@code &timestamp=..&sign=..} (url-encoded sign) to the robot webhook URL. */
    public static String signedUrl(String webhookUrl, String secret, String timestamp) {
        String sign = URLEncoder.encode(sign(timestamp, secret), StandardCharsets.UTF_8);
        String sep = webhookUrl.contains("?") ? "&" : "?";
        return webhookUrl + sep + "timestamp=" + timestamp + "&sign=" + sign;
    }

    /** Build the DingTalk text message body: {@code {"msgtype":"text","text":{"content":...}}}. */
    public static String buildTextPayload(String content) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("msgtype", "text");
            ObjectNode text = root.putObject("text");
            text.put("content", content == null ? "" : content);
            return MAPPER.writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{\"msgtype\":\"text\",\"text\":{\"content\":\"\"}}";
        }
    }

    /** Extract {@code text.content} from an outgoing-robot POST body; blank/absent yields empty. */
    public static String extractMessage(String body) {
        JsonNode root = parse(body);
        if (root == null) {
            return "";
        }
        JsonNode text = root.get("text");
        if (text != null && text.isObject()) {
            JsonNode content = text.get("content");
            if (content != null && content.isTextual()) {
                return content.asText().strip();
            }
        }
        return "";
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
}
