package io.pigagent.channel;

import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.Msg;
import io.pigagent.core.agent.AgentHolder;
import io.pigagent.core.agent.PigAgent;
import io.pigagent.core.agent.kernel.AgentKernel;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Bridge routing tests: inbound → agent stream → outbound reply, with a mocked agent + kernel. */
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

    private static AgentHolder holderStreaming(Flux<Event> stream) {
        PigAgent agent = mock(PigAgent.class);
        when(agent.getAgentName()).thenReturn("A");
        when(agent.stream(any(Msg.class))).thenReturn(stream);
        return new AgentHolder(agent);
    }

    @Test
    void inboundMessageIsRoutedAndReplySentBack() {
        // Arrange
        Event event = mock(Event.class);
        when(event.getType()).thenReturn(EventType.AGENT_RESULT);
        Msg replyMsg = mock(Msg.class);
        when(replyMsg.getTextContent()).thenReturn("pong");
        when(event.getMessage()).thenReturn(replyMsg);

        AgentHolder holder = holderStreaming(Flux.just(event));
        AgentKernel kernel = mock(AgentKernel.class);
        CapturingChannel channel = new CapturingChannel();
        ChannelAgentBridge bridge = new ChannelAgentBridge(holder, channel, kernel);
        bridge.start();

        // Act
        channel.fire("ping");

        // Assert
        assertThat(channel.sent).containsExactly("pong");
        verify(kernel).noteChannelChat("testchan");
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
