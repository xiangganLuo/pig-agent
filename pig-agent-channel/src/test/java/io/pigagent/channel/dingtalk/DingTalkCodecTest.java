package io.pigagent.channel.dingtalk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure tests for DingTalk custom-robot signing + message mapping. */
class DingTalkCodecTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void signIsDeterministicAndBase64() {
        // Arrange
        String ts = "1700000000000";
        String secret = "SEC-abc123";

        // Act
        String a = DingTalkCodec.sign(ts, secret);
        String b = DingTalkCodec.sign(ts, secret);

        // Assert: stable + valid base64 (decodes to 32 bytes for HMAC-SHA256)
        assertThat(a).isEqualTo(b).isNotBlank();
        assertThat(Base64.getDecoder().decode(a)).hasSize(32);
    }

    @Test
    void verifyAcceptsValidAndRejectsTamperedOrNull() {
        // Arrange
        String ts = "1700000000000";
        String secret = "s3cr3t";
        String sign = DingTalkCodec.sign(ts, secret);

        // Act + Assert
        assertThat(DingTalkCodec.verify(ts, sign, secret)).isTrue();
        assertThat(DingTalkCodec.verify(ts, sign + "x", secret)).isFalse();
        assertThat(DingTalkCodec.verify("1700000000001", sign, secret)).isFalse();
        assertThat(DingTalkCodec.verify(ts, sign, "other")).isFalse();
        assertThat(DingTalkCodec.verify(null, sign, secret)).isFalse();
        assertThat(DingTalkCodec.verify(ts, null, secret)).isFalse();
        assertThat(DingTalkCodec.verify(ts, sign, null)).isFalse();
    }

    @Test
    void signedUrlAppendsTimestampAndUrlEncodedSign() {
        // Arrange
        String ts = "1700000000000";
        String secret = "s3cr3t";
        String rawSign = DingTalkCodec.sign(ts, secret);

        // Act
        String url = DingTalkCodec.signedUrl("https://oapi.dingtalk.com/robot/send?access_token=T", secret, ts);

        // Assert: uses & (URL already has ?), carries timestamp + url-encoded sign
        assertThat(url).contains("&timestamp=" + ts).contains("&sign=");
        assertThat(url).contains(URLEncoder.encode(rawSign, StandardCharsets.UTF_8));
    }

    @Test
    void signedUrlUsesQuestionMarkWhenNoQuery() {
        String url = DingTalkCodec.signedUrl("https://example.com/robot", "s", "1");
        assertThat(url).contains("robot?timestamp=1&sign=");
    }

    @Test
    void buildTextPayloadProducesDingTalkTextShape() throws Exception {
        // Act
        String payload = DingTalkCodec.buildTextPayload("hello \"agent\"");

        // Assert: proper nesting + escaping
        JsonNode node = MAPPER.readTree(payload);
        assertThat(node.get("msgtype").asText()).isEqualTo("text");
        assertThat(node.get("text").get("content").asText()).isEqualTo("hello \"agent\"");
    }

    @Test
    void extractMessageReadsTextContent() {
        String body = "{\"msgtype\":\"text\",\"text\":{\"content\":\"  hi there  \"},\"senderNick\":\"u\"}";
        assertThat(DingTalkCodec.extractMessage(body)).isEqualTo("hi there");
    }

    @Test
    void extractMessageEmptyForBlankOrMalformedOrMissing() {
        assertThat(DingTalkCodec.extractMessage(null)).isEmpty();
        assertThat(DingTalkCodec.extractMessage("   ")).isEmpty();
        assertThat(DingTalkCodec.extractMessage("not json")).isEmpty();
        assertThat(DingTalkCodec.extractMessage("{\"msgtype\":\"text\"}")).isEmpty();
    }
}
