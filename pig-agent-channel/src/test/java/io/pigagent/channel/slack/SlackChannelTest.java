package io.pigagent.channel.slack;

import io.pigagent.channel.http.InboundHttp;
import io.pigagent.channel.http.OutboundHttp;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for the Slack channel's request processing (signature, handshake, dispatch). */
class SlackChannelTest {

    private static InboundHttp post(String body, Map<String, String> headers) {
        return new InboundHttp("POST", headers, body);
    }

    @Test
    void urlVerificationEchoesChallenge() {
        // Arrange: no signing secret
        SlackChannel channel = new SlackChannel(0, null, null);

        // Act
        OutboundHttp out = channel.process(post(
                "{\"type\":\"url_verification\",\"challenge\":\"C42\"}", Map.of()));

        // Assert
        assertThat(out.status()).isEqualTo(200);
        assertThat(out.body()).isEqualTo("C42");
    }

    @Test
    void messageEventTriggersHandlerAndAcks() {
        // Arrange
        AtomicReference<String> seen = new AtomicReference<>();
        SlackChannel channel = new SlackChannel(0, null, null);
        channel.bind(seen::set);

        // Act
        OutboundHttp out = channel.process(post(
                "{\"type\":\"event_callback\",\"event\":{\"type\":\"message\",\"text\":\"hi\"}}", Map.of()));

        // Assert
        assertThat(seen.get()).isEqualTo("hi");
        assertThat(out.status()).isEqualTo(200);
        assertThat(out.body()).isNull(); // empty ack; reply delivered out-of-band (stub)
    }

    @Test
    void nonPostYields405() {
        SlackChannel channel = new SlackChannel(0, null, null);
        assertThat(channel.process(new InboundHttp("GET", Map.of(), "")).status()).isEqualTo(405);
    }

    @Test
    void invalidSignatureRejectedWhenSecretConfigured() {
        // Arrange
        SlackChannel channel = new SlackChannel(0, null, "secret");
        Map<String, String> headers = new HashMap<>();
        headers.put("x-slack-request-timestamp", "1000");
        headers.put("x-slack-signature", "v0=bogus");

        // Act
        OutboundHttp out = channel.process(post("{\"type\":\"url_verification\",\"challenge\":\"C\"}", headers));

        // Assert
        assertThat(out.status()).isEqualTo(401);
    }

    @Test
    void validSignatureAcceptedWhenSecretConfigured() {
        // Arrange
        String secret = "secret";
        String ts = "1000";
        String body = "{\"type\":\"url_verification\",\"challenge\":\"C\"}";
        Map<String, String> headers = new HashMap<>();
        headers.put("x-slack-request-timestamp", ts);
        headers.put("x-slack-signature", SlackEventCodec.sign(secret, ts, body));
        SlackChannel channel = new SlackChannel(0, null, secret);

        // Act
        OutboundHttp out = channel.process(post(body, headers));

        // Assert
        assertThat(out.status()).isEqualTo(200);
        assertThat(out.body()).isEqualTo("C");
    }

    @Test
    void sendMessageIsSafeStub() {
        SlackChannel channel = new SlackChannel(0, null, null);
        channel.sendMessage("reply"); // logs only, must not throw
        assertThat(channel.channelId()).isEqualTo("slack");
        assertThat(channel.effectivePort()).isEqualTo(SlackChannel.DEFAULT_PORT);
    }
}
