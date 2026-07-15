package io.pigagent.channel.slack;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure tests for Slack event mapping + signature verification. */
class SlackEventCodecTest {

    @Test
    void urlVerificationReturnsChallenge() {
        String body = "{\"type\":\"url_verification\",\"challenge\":\"abc123\"}";
        assertThat(SlackEventCodec.challenge(body)).contains("abc123");
    }

    @Test
    void nonVerificationHasNoChallenge() {
        assertThat(SlackEventCodec.challenge("{\"type\":\"event_callback\"}")).isEmpty();
        assertThat(SlackEventCodec.challenge("not json")).isEmpty();
    }

    @Test
    void extractsMessageEventText() {
        String body = "{\"type\":\"event_callback\",\"event\":{\"type\":\"message\",\"text\":\"hello agent\"}}";
        assertThat(SlackEventCodec.extractText(body)).contains("hello agent");
    }

    @Test
    void ignoresBotAndSubtypeMessages() {
        String bot = "{\"type\":\"event_callback\",\"event\":{\"type\":\"message\",\"text\":\"x\",\"bot_id\":\"B1\"}}";
        String sub = "{\"type\":\"event_callback\",\"event\":{\"type\":\"message\",\"text\":\"x\","
                + "\"subtype\":\"message_changed\"}}";
        assertThat(SlackEventCodec.extractText(bot)).isEmpty();
        assertThat(SlackEventCodec.extractText(sub)).isEmpty();
    }

    @Test
    void ignoresNonMessageEvents() {
        String body = "{\"type\":\"event_callback\",\"event\":{\"type\":\"reaction_added\"}}";
        assertThat(SlackEventCodec.extractText(body)).isEmpty();
    }

    @Test
    void validSignatureVerifies() {
        // Arrange
        String secret = "8f742231b10e8888abcd99yyyzzz85a5";
        String ts = "1531420618";
        String body = "{\"type\":\"url_verification\",\"challenge\":\"x\"}";
        String sig = SlackEventCodec.sign(secret, ts, body);

        // Act + Assert
        assertThat(sig).startsWith("v0=");
        assertThat(SlackEventCodec.verifySignature(secret, ts, body, sig)).isTrue();
    }

    @Test
    void tamperedSignatureOrBodyFails() {
        String secret = "s3cr3t";
        String ts = "1000";
        String body = "{\"a\":1}";
        String sig = SlackEventCodec.sign(secret, ts, body);

        assertThat(SlackEventCodec.verifySignature(secret, ts, "{\"a\":2}", sig)).isFalse();
        assertThat(SlackEventCodec.verifySignature(secret, ts, body, "v0=deadbeef")).isFalse();
        assertThat(SlackEventCodec.verifySignature(secret, ts, body, null)).isFalse();
    }
}
