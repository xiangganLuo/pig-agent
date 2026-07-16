package io.pigagent.channel;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.tool.Toolkit;
import io.pigagent.channel.gateway.GatewayChannelKernel;
import io.pigagent.core.agent.AgentHolder;
import io.pigagent.core.agent.PigAgent;
import io.pigagent.core.agent.kernel.AgentKernel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bridge routing tests: inbound → agent stream → outbound reply, with a mocked agent + kernel.
 *
 * <p>av2 Phase 4: the bridge threads a channel-owned session id ({@code "channel:<id>"}) into the
 * session-aware {@code stream(Msg, sessionId)} and aggregates the typed {@link AgentEvent} stream
 * (answer text from {@link TextBlockDeltaEvent}).
 */
class ChannelAgentBridgeTest {

    /** A test channel that captures the inbound handler and records outbound messages. */
    private static final class CapturingChannel implements Channel {
        private Consumer<String> handler;
        final List<String> sent = new ArrayList<>();

        void fire(String input) {
            handler.accept(input);
        }

        @Override public String channelId() { return "testchan"; }
        @Override public String displayName() { return "Test Channel"; }
        @Override public void start(Consumer<String> messageHandler) { this.handler = messageHandler; }
        @Override public void sendMessage(String message) { sent.add(message); }
        @Override public void stop() { }
        @Override public boolean isRunning() { return true; }
    }

    private static AgentHolder holderStreaming(Flux<AgentEvent> stream) {
        PigAgent agent = mock(PigAgent.class);
        when(agent.getAgentName()).thenReturn("A");
        when(agent.stream(any(Msg.class), anyString())).thenReturn(stream);
        return new AgentHolder(agent);
    }

    @Test
    void inboundMessageIsRoutedAndReplySentBack() {
        // Arrange: a single answer delta "pong"
        AgentEvent event = new TextBlockDeltaEvent("r1", "b1", "pong");

        AgentHolder holder = holderStreaming(Flux.just(event));
        AgentKernel kernel = mock(AgentKernel.class);
        CapturingChannel channel = new CapturingChannel();
        ChannelAgentBridge bridge = new ChannelAgentBridge(holder, channel, kernel);
        bridge.start();

        // Act
        channel.fire("ping");

        // Assert: reply routed back, turn bound to the channel-owned session id
        assertThat(channel.sent).containsExactly("pong");
        verify(kernel).noteChannelChat("testchan");
        verify(holder.get()).stream(any(Msg.class), eq("channel:testchan"));
    }

    @Test
    void blankInputIsIgnored() {
        AgentHolder holder = holderStreaming(Flux.empty());
        CapturingChannel channel = new CapturingChannel();
        ChannelAgentBridge bridge = new ChannelAgentBridge(holder, channel, null);
        bridge.start();

        // Act
        channel.fire("   ");

        // Assert: no agent turn, no reply
        assertThat(channel.sent).isEmpty();
    }

    /** Fake model answering fixed text — for the native-gateway routing path. */
    private static final class TextModel implements Model {
        @Override public String getModelName() { return "fake-text"; }
        @Override public Flux<ChatResponse> stream(List<Msg> m, List<ToolSchema> t, GenerateOptions o) {
            return Flux.just(ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text("gw-reply").build()))
                    .finishReason("stop").build());
        }
    }

    @Test
    void gatewayRoutingBypassesTheDirectAgentStream(@TempDir Path ws) {
        // A separate real agent + native gateway kernel handles the turn; the holder's (direct-path)
        // agent must NOT be streamed when the gateway kernel is supplied (opt-in routing).
        PigAgent gatewayAgent = PigAgent.builder().name("gw").sysPrompt("sp").model(new TextModel())
                .toolkit(new Toolkit()).workspace(ws).build();
        GatewayChannelKernel kernel = GatewayChannelKernel.forAgent(gatewayAgent.getHarnessAgent());

        PigAgent directAgent = mock(PigAgent.class);
        when(directAgent.getAgentName()).thenReturn("direct");
        AgentHolder holder = new AgentHolder(directAgent);
        CapturingChannel channel = new CapturingChannel();
        ChannelAgentBridge bridge = new ChannelAgentBridge(holder, channel, null, kernel);
        bridge.start();

        channel.fire("ping");

        // Routed through the native gateway, NOT the direct per-session agent stream.
        verify(directAgent, never()).stream(any(Msg.class), anyString());
        assertThat(channel.sent).as("the gateway agent's reply was delivered").containsExactly("gw-reply");
        gatewayAgent.close();
    }

    @Test
    void agentErrorSurfacesAsOutboundError() {
        // Arrange
        AgentHolder holder = holderStreaming(Flux.error(new RuntimeException("boom")));
        CapturingChannel channel = new CapturingChannel();
        ChannelAgentBridge bridge = new ChannelAgentBridge(holder, channel, null);
        bridge.start();

        // Act
        channel.fire("ping");

        // Assert: error surfaced back to the channel, without leaking anything but the message
        assertThat(channel.sent).isNotEmpty();
        assertThat(channel.sent).allSatisfy(s -> assertThat(s).contains("boom"));
        assertThat(channel.sent).allSatisfy(s -> assertThat(s).startsWith("Error:"));
    }
}
