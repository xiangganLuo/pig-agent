package io.pigagent.channel.strategy;

import io.pigagent.channel.http.InboundHttp;
import io.pigagent.channel.http.OutboundHttp;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for the generic strategy-driven HTTP channel (fake strategy, no socket for process). */
class StrategyHttpChannelTest {

    /** A configurable fake strategy that records what was sent and returns a scripted inbound. */
    private static final class FakeStrategy implements ChannelStrategy {
        Inbound next = Inbound.ignore();
        final List<String> sent = new ArrayList<>();

        @Override public String channelId() { return "fake"; }
        @Override public String displayName() { return "Fake Robot"; }
        @Override public int defaultPort() { return 8600; }
        @Override public String defaultPath() { return "/fake"; }
        @Override public Inbound inbound(InboundHttp request) { return next; }
        @Override public OutboundHttp ack() { return OutboundHttp.json(200, "{\"ok\":true}"); }
        @Override public boolean send(String agentReply) { sent.add(agentReply); return true; }
    }

    private static InboundHttp post() {
        return new InboundHttp("POST", Map.of(), "{}");
    }

    @Test
    void directResponseIsWrittenBackWithoutRouting() {
        // Arrange
        FakeStrategy strategy = new FakeStrategy();
        strategy.next = Inbound.respond(OutboundHttp.json(401, "{\"error\":\"unauthorized\"}"));
        StrategyHttpChannel channel = new StrategyHttpChannel(strategy, 0, null);
        List<String> routed = new ArrayList<>();
        channel.bind(routed::add);

        // Act
        OutboundHttp out = channel.process(post());

        // Assert
        assertThat(out.status()).isEqualTo(401);
        assertThat(routed).isEmpty();
    }

    @Test
    void messageIsRoutedToHandlerAndReplyGoesOutViaStrategySend() {
        // Arrange: handler simulates the bridge — runs and pushes the reply through sendMessage
        FakeStrategy strategy = new FakeStrategy();
        strategy.next = Inbound.route("ping");
        StrategyHttpChannel channel = new StrategyHttpChannel(strategy, 0, null);
        channel.bind(msg -> channel.sendMessage("pong:" + msg));

        // Act
        OutboundHttp out = channel.process(post());

        // Assert: HTTP body is the fixed ack; reply delivered out-of-band via strategy.send
        assertThat(out.status()).isEqualTo(200);
        assertThat(out.body()).contains("ok");
        assertThat(strategy.sent).containsExactly("pong:ping");
    }

    @Test
    void ignoredInboundReturnsAckAndDoesNotRoute() {
        FakeStrategy strategy = new FakeStrategy();
        strategy.next = Inbound.ignore();
        StrategyHttpChannel channel = new StrategyHttpChannel(strategy, 0, null);
        List<String> routed = new ArrayList<>();
        channel.bind(routed::add);

        OutboundHttp out = channel.process(post());

        assertThat(out.status()).isEqualTo(200);
        assertThat(routed).isEmpty();
    }

    @Test
    void identityAndEffectivePortPathFallBackToStrategyDefaults() {
        FakeStrategy strategy = new FakeStrategy();
        StrategyHttpChannel channel = new StrategyHttpChannel(strategy, 0, "  ");
        assertThat(channel.channelId()).isEqualTo("fake");
        assertThat(channel.displayName()).isEqualTo("Fake Robot");
        assertThat(channel.effectivePort()).isEqualTo(8600);
        assertThat(channel.effectivePath()).isEqualTo("/fake");
        assertThat(channel.isRunning()).isFalse();
    }

    @Test
    void configuredPortAndPathOverrideDefaults() {
        FakeStrategy strategy = new FakeStrategy();
        StrategyHttpChannel channel = new StrategyHttpChannel(strategy, 9100, "/custom");
        assertThat(channel.effectivePort()).isEqualTo(9100);
        assertThat(channel.effectivePath()).isEqualTo("/custom");
    }

    @Test
    void startBindsAndStopReleases() throws Exception {
        // Arrange: a free port to avoid conflicts
        int freePort;
        try (ServerSocket s = new ServerSocket(0)) {
            freePort = s.getLocalPort();
        }
        StrategyHttpChannel channel = new StrategyHttpChannel(new FakeStrategy(), freePort, "/fake");

        // Act
        channel.start(msg -> { });

        // Assert
        assertThat(channel.isRunning()).isTrue();
        assertThat(channel.boundPort()).isEqualTo(freePort);

        // Cleanup
        channel.stop();
        assertThat(channel.isRunning()).isFalse();
    }
}
