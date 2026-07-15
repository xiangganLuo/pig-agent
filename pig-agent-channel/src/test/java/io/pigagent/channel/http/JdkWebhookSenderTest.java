package io.pigagent.channel.http;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Loopback round-trip for the JDK-backed outbound sender: start an {@link HttpChannelServer} on an
 * ephemeral port, POST through {@link JdkWebhookSender}, and assert the server received the body and
 * the sender reports success. Offline (localhost only) and deterministic.
 */
class JdkWebhookSenderTest {

    @Test
    void postDeliversBodyAndReturnsTrueOn2xx() throws Exception {
        // Arrange: a server that records the received body and replies 200
        HttpChannelServer server = new HttpChannelServer();
        AtomicReference<String> received = new AtomicReference<>();
        RequestHandler handler = req -> {
            received.set(req.body());
            return OutboundHttp.json(200, "{}");
        };
        try {
            server.start(0, "/hook", handler);
            String url = "http://localhost:" + server.boundPort() + "/hook";

            // Act
            boolean ok = new JdkWebhookSender().post(url, "{\"msg_type\":\"text\"}");

            // Assert
            assertThat(ok).isTrue();
            assertThat(received.get()).isEqualTo("{\"msg_type\":\"text\"}");
        } finally {
            server.stop();
        }
    }

    @Test
    void nonSuccessStatusReturnsFalse() throws Exception {
        HttpChannelServer server = new HttpChannelServer();
        try {
            server.start(0, "/hook", req -> OutboundHttp.json(500, "{\"error\":\"x\"}"));
            String url = "http://localhost:" + server.boundPort() + "/hook";

            assertThat(new JdkWebhookSender().post(url, "{}")).isFalse();
        } finally {
            server.stop();
        }
    }

    @Test
    void blankOrUnreachableUrlReturnsFalseWithoutThrowing() {
        JdkWebhookSender sender = new JdkWebhookSender();
        assertThat(sender.post(null, "{}")).isFalse();
        assertThat(sender.post("   ", "{}")).isFalse();
        assertThat(sender.post("not-a-valid-url", "{}")).isFalse();
    }
}
