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

    @Test
    void robotErrorCodeInA200BodyIsTreatedAsFailure() throws Exception {
        // DingTalk/Feishu answer 200 even when they reject the message (errcode != 0). The sender must
        // read the body and report failure, so /notify test never claims a delivery that was rejected.
        HttpChannelServer server = new HttpChannelServer();
        try {
            server.start(0, "/hook",
                    req -> OutboundHttp.json(200, "{\"errcode\":310000,\"errmsg\":\"sign not match\"}"));
            String url = "http://localhost:" + server.boundPort() + "/hook";

            assertThat(new JdkWebhookSender().post(url, "{}")).isFalse();
        } finally {
            server.stop();
        }
    }

    @Test
    void zeroErrcodeAckIsSuccess() throws Exception {
        HttpChannelServer server = new HttpChannelServer();
        try {
            server.start(0, "/hook", req -> OutboundHttp.json(200, "{\"errcode\":0,\"errmsg\":\"ok\"}"));
            String url = "http://localhost:" + server.boundPort() + "/hook";

            assertThat(new JdkWebhookSender().post(url, "{}")).isTrue();
        } finally {
            server.stop();
        }
    }

    @Test
    void robotErrorCodeParsesEachPlatformField() {
        // DingTalk uses errcode, Feishu uses code / StatusCode; a plain ack or non-JSON body → 0 (ok).
        assertThat(JdkWebhookSender.robotErrorCode("{\"errcode\":123}")).isEqualTo(123L);
        assertThat(JdkWebhookSender.robotErrorCode("{\"code\":19021,\"msg\":\"bad\"}")).isEqualTo(19021L);
        assertThat(JdkWebhookSender.robotErrorCode("{\"StatusCode\":9}")).isEqualTo(9L);
        assertThat(JdkWebhookSender.robotErrorCode("{}")).isZero();
        assertThat(JdkWebhookSender.robotErrorCode("")).isZero();
        assertThat(JdkWebhookSender.robotErrorCode("not json")).isZero();
    }
}
