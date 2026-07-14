package io.pigagent.channel.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure mapping tests for the webhook inbound/outbound codec. */
class WebhookCodecTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void extractsMessageKeyFromJson() {
        assertThat(WebhookCodec.extractMessage("{\"message\":\"hi there\"}", "application/json"))
                .isEqualTo("hi there");
    }

    @Test
    void extractsTextKeyWhenNoMessageKey() {
        assertThat(WebhookCodec.extractMessage("{\"text\":\"hello\"}", "application/json"))
                .isEqualTo("hello");
    }

    @Test
    void prefersMessageOverText() {
        assertThat(WebhookCodec.extractMessage("{\"message\":\"m\",\"text\":\"t\"}", "application/json"))
                .isEqualTo("m");
    }

    @Test
    void fallsBackToPlainTextBody() {
        assertThat(WebhookCodec.extractMessage("just some text", "text/plain"))
                .isEqualTo("just some text");
    }

    @Test
    void malformedJsonFallsBackToRawBody() {
        // not valid JSON despite the leading brace → treated as plain text
        assertThat(WebhookCodec.extractMessage("{not json", "application/json"))
                .isEqualTo("{not json");
    }

    @Test
    void jsonWithoutKnownKeysFallsBackToWholeBody() {
        assertThat(WebhookCodec.extractMessage("{\"foo\":\"bar\"}", "application/json"))
                .isEqualTo("{\"foo\":\"bar\"}");
    }

    @Test
    void blankAndNullBodyYieldEmpty() {
        assertThat(WebhookCodec.extractMessage(null, null)).isEmpty();
        assertThat(WebhookCodec.extractMessage("   ", "text/plain")).isEmpty();
    }

    @Test
    void formatReplyProducesEscapedJson() throws Exception {
        // Act
        String out = WebhookCodec.formatReply("line1\n\"quoted\"");

        // Assert: parses back to the exact reply (escaping is correct)
        assertThat(mapper.readTree(out).get("reply").asText()).isEqualTo("line1\n\"quoted\"");
    }

    @Test
    void formatReplyHandlesNull() throws Exception {
        assertThat(mapper.readTree(WebhookCodec.formatReply(null)).get("reply").asText()).isEmpty();
    }
}
