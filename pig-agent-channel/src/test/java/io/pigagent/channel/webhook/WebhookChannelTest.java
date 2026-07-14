package io.pigagent.channel.webhook;

import io.pigagent.channel.http.InboundHttp;
import io.pigagent.channel.http.OutboundHttp;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for the webhook channel's pure request processing, reply capture, and lifecycle. */
class WebhookChannelTest {

    private static InboundHttp post(String body, Map<String, String> headers) {
        return new InboundHttp("POST", headers, body);
    }

    @Test
    void nonPostMethodYields405() {
        WebhookChannel channel = new WebhookChannel(0, null, null);
        OutboundHttp out = channel.process(new InboundHttp("GET", Map.of(), ""));
        assertThat(out.status()).isEqualTo(405);
    }

    @Test
    void emptyMessageYields400() {
        WebhookChannel channel = new WebhookChannel(0, null, null);
        OutboundHttp out = channel.process(post("   ", Map.of()));
        assertThat(out.status()).isEqualTo(400);
    }

    @Test
    void postMessageIsRoutedAndReplyReturned() {
        // Arrange
        WebhookChannel channel = new WebhookChannel(0, null, null);
        channel.bind(text -> channel.sendMessage("echo:" + text));

        // Act
        OutboundHttp out = channel.process(post("{\"message\":\"hi\"}",
                Map.of("content-type", "application/json")));

        // Assert
        assertThat(out.status()).isEqualTo(200);
        assertThat(out.body()).contains("echo:hi");
    }

    @Test
    void authConfiguredRejectsMissingAndWrongToken() {
        WebhookChannel channel = new WebhookChannel(0, null, "sekret");
        assertThat(channel.process(post("{\"message\":\"hi\"}", Map.of())).status()).isEqualTo(401);
        assertThat(channel.process(post("{\"message\":\"hi\"}",
                Map.of("x-auth-token", "wrong"))).status()).isEqualTo(401);
    }

    @Test
    void authConfiguredAcceptsCorrectTokenHeaderAndBearer() {
        WebhookChannel channel = new WebhookChannel(0, null, "sekret");
        channel.bind(text -> channel.sendMessage("ok"));

        assertThat(channel.process(post("{\"message\":\"hi\"}",
                Map.of("x-auth-token", "sekret"))).status()).isEqualTo(200);
        assertThat(channel.process(post("{\"message\":\"hi\"}",
                Map.of("authorization", "Bearer sekret"))).status()).isEqualTo(200);
    }

    @Test
    void noTokenConfiguredSkipsAuth() {
        WebhookChannel channel = new WebhookChannel(0, null, null);
        channel.bind(text -> channel.sendMessage("ok"));
        assertThat(channel.process(post("plain text", Map.of())).status()).isEqualTo(200);
    }

    @Test
    void runAndCaptureCollectsSentReply() {
        WebhookChannel channel = new WebhookChannel(0, null, null);
        channel.bind(text -> channel.sendMessage("reply-to-" + text));
        assertThat(channel.runAndCapture("q")).isEqualTo("reply-to-q");
    }

    @Test
    void sendMessageOutsideRequestScopeIsDiscarded() {
        // Arrange: no active capture buffer (not inside runAndCapture)
        WebhookChannel channel = new WebhookChannel(0, null, null);

        // Act + Assert: does not throw, silently discarded
        channel.sendMessage("orphan");
    }

    @Test
    void identityAndDefaults() {
        WebhookChannel channel = new WebhookChannel(0, "", "");
        assertThat(channel.channelId()).isEqualTo("webhook");
        assertThat(channel.displayName()).isEqualTo("HTTP Webhook");
        assertThat(channel.effectivePort()).isEqualTo(WebhookChannel.DEFAULT_PORT);
        assertThat(channel.isRunning()).isFalse();
    }

    @Test
    void startBindsAndStopReleases() throws Exception {
        // Arrange: find a free port to avoid conflicts
        int freePort;
        try (ServerSocket s = new ServerSocket(0)) {
            freePort = s.getLocalPort();
        }
        WebhookChannel channel = new WebhookChannel(freePort, "/hook", null);

        // Act
        channel.start(text -> channel.sendMessage("ok"));

        // Assert
        assertThat(channel.isRunning()).isTrue();
        assertThat(channel.boundPort()).isEqualTo(freePort);

        // Cleanup
        channel.stop();
        assertThat(channel.isRunning()).isFalse();
    }
}
