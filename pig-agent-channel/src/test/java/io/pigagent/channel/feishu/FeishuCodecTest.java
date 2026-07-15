package io.pigagent.channel.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure tests for Feishu/Lark event mapping, signature verification, and outbound signing. */
class FeishuCodecTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void challengeExtractedFromUrlVerification() {
        String body = "{\"type\":\"url_verification\",\"challenge\":\"C-123\",\"token\":\"tok\"}";
        assertThat(FeishuCodec.challenge(body)).contains("C-123");
    }

    @Test
    void noChallengeForNonVerificationOrMalformed() {
        assertThat(FeishuCodec.challenge("{\"type\":\"event_callback\"}")).isEmpty();
        assertThat(FeishuCodec.challenge("not json")).isEmpty();
        assertThat(FeishuCodec.challenge(null)).isEmpty();
    }

    @Test
    void extractMessageReadsNestedTextContentOfReceiveEvent() {
        // content is itself a JSON string: {"text":"hello agent"}
        String body = "{\"schema\":\"2.0\","
                + "\"header\":{\"event_type\":\"im.message.receive_v1\"},"
                + "\"event\":{\"message\":{\"message_type\":\"text\","
                + "\"content\":\"{\\\"text\\\":\\\"  hello agent  \\\"}\"}}}";
        assertThat(FeishuCodec.extractMessage(body)).contains("hello agent");
    }

    @Test
    void extractMessageEmptyForNonReceiveOrNonTextOrMalformed() {
        String wrongEvent = "{\"header\":{\"event_type\":\"im.chat.updated_v1\"},"
                + "\"event\":{\"message\":{\"message_type\":\"text\",\"content\":\"{\\\"text\\\":\\\"x\\\"}\"}}}";
        String nonText = "{\"header\":{\"event_type\":\"im.message.receive_v1\"},"
                + "\"event\":{\"message\":{\"message_type\":\"image\",\"content\":\"{}\"}}}";
        assertThat(FeishuCodec.extractMessage(wrongEvent)).isEmpty();
        assertThat(FeishuCodec.extractMessage(nonText)).isEmpty();
        assertThat(FeishuCodec.extractMessage("not json")).isEmpty();
        assertThat(FeishuCodec.extractMessage(null)).isEmpty();
    }

    @Test
    void encryptFieldReadOrEmpty() {
        assertThat(FeishuCodec.encryptField("{\"encrypt\":\"BLOB\"}")).isEqualTo("BLOB");
        assertThat(FeishuCodec.encryptField("{\"type\":\"x\"}")).isEmpty();
        assertThat(FeishuCodec.encryptField(null)).isEmpty();
    }

    @Test
    void signatureIsDeterministicSha256Hex() {
        // Act
        String a = FeishuCodec.signature("1700000000", "nonce1", "ENC", "tok");
        String b = FeishuCodec.signature("1700000000", "nonce1", "ENC", "tok");

        // Assert: stable 64-char lowercase hex
        assertThat(a).isEqualTo(b).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void verifySignatureAcceptsValidRejectsTamperedOrNull() {
        // Arrange
        String token = "verif-token";
        String ts = "1700000000";
        String nonce = "nonce-xyz";
        String encrypt = "ENCBLOB";
        String sig = FeishuCodec.signature(ts, nonce, encrypt, token);

        // Act + Assert
        assertThat(FeishuCodec.verifySignature(token, ts, nonce, encrypt, sig)).isTrue();
        assertThat(FeishuCodec.verifySignature(token, ts, nonce, encrypt, sig + "0")).isFalse();
        assertThat(FeishuCodec.verifySignature(token, ts, "other", encrypt, sig)).isFalse();
        assertThat(FeishuCodec.verifySignature("wrong", ts, nonce, encrypt, sig)).isFalse();
        assertThat(FeishuCodec.verifySignature(token, null, nonce, encrypt, sig)).isFalse();
        assertThat(FeishuCodec.verifySignature(token, ts, nonce, encrypt, null)).isFalse();
    }

    @Test
    void outboundSignIsDeterministicBase64() {
        String a = FeishuCodec.sign("1700000000", "SECRET");
        String b = FeishuCodec.sign("1700000000", "SECRET");
        assertThat(a).isEqualTo(b).isNotBlank();
        assertThat(Base64.getDecoder().decode(a)).hasSize(32);
    }

    @Test
    void buildTextPayloadShape() throws Exception {
        JsonNode node = MAPPER.readTree(FeishuCodec.buildTextPayload("hi"));
        assertThat(node.get("msg_type").asText()).isEqualTo("text");
        assertThat(node.get("content").get("text").asText()).isEqualTo("hi");
        assertThat(node.has("sign")).isFalse();
    }

    @Test
    void buildSignedTextPayloadCarriesTimestampAndSign() throws Exception {
        JsonNode node = MAPPER.readTree(FeishuCodec.buildSignedTextPayload("hi", "1700000000", "SIG=="));
        assertThat(node.get("timestamp").asText()).isEqualTo("1700000000");
        assertThat(node.get("sign").asText()).isEqualTo("SIG==");
        assertThat(node.get("msg_type").asText()).isEqualTo("text");
        assertThat(node.get("content").get("text").asText()).isEqualTo("hi");
    }

    @Test
    void buildChallengeResponseEscapesProperly() throws Exception {
        JsonNode node = MAPPER.readTree(FeishuCodec.buildChallengeResponse("a\"b"));
        assertThat(node.get("challenge").asText()).isEqualTo("a\"b");
    }
}
