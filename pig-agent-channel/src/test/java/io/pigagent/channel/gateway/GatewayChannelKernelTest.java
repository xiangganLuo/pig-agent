package io.pigagent.channel.gateway;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import io.agentscope.harness.agent.gateway.channel.chatui.SendOptions;
import io.pigagent.core.agent.PigAgent;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * av2 Gateway enhancement — {@link GatewayChannelKernel} wiring over pig's {@code HarnessAgent} vehicle.
 * Proves (offline, fake model + {@code @TempDir}): the native {@code Gateway}/{@code ChatUiChannel} routes
 * a turn and returns the agent's reply; the stream carries typed events; {@link SendOptions} threads an
 * isolated, continuing conversation per {@code (userId,sessionId)} (consistent with pig's Phase-3
 * per-session {@code AgentStateStore}); and attached native channels start/stop with the kernel.
 */
class GatewayChannelKernelTest {

    private static Msg user(String text) {
        return Msg.builder().name("user").role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build()).build();
    }

    private static ChatResponse text(String s) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(s).build())).finishReason("stop").build();
    }

    private static String allText(Msg m) {
        StringBuilder sb = new StringBuilder();
        if (m != null) {
            for (ContentBlock b : m.getContent()) {
                if (b instanceof TextBlock t) {
                    sb.append(t.getText());
                }
            }
        }
        return sb.toString();
    }

    /** Always answers a fixed text. */
    static final class TextModel implements Model {
        private final String answer;
        TextModel(String answer) { this.answer = answer; }
        @Override public String getModelName() { return "fake-text"; }
        @Override public Flux<ChatResponse> stream(List<Msg> m, List<ToolSchema> t, GenerateOptions o) {
            return Flux.just(text(answer));
        }
    }

    /** Records the user messages it sees each turn (to prove session continuation/isolation). */
    static final class CapturingModel implements Model {
        final List<List<Msg>> seen = new ArrayList<>();
        @Override public String getModelName() { return "fake-capturing"; }
        @Override public Flux<ChatResponse> stream(List<Msg> m, List<ToolSchema> t, GenerateOptions o) {
            seen.add(new ArrayList<>(m));
            return Flux.just(text("ack"));
        }
        int userMsgCount(int turnIndex) {
            int n = 0;
            for (Msg msg : seen.get(turnIndex)) {
                if (msg.getRole() == MsgRole.USER) {
                    n++;
                }
            }
            return n;
        }
    }

    /** A minimal native gateway channel that records its lifecycle + deliveries. */
    static final class StubNativeChannel implements io.agentscope.harness.agent.gateway.channel.Channel {
        final AtomicInteger inits = new AtomicInteger();
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger stops = new AtomicInteger();
        final List<Msg> delivered = new ArrayList<>();
        @Override public String channelId() { return "stub"; }
        @Override public ChannelConfig config() { return ChannelConfig.of("stub", "default"); }
        @Override public void init(Gateway gateway) { inits.incrementAndGet(); }
        @Override public void start() { starts.incrementAndGet(); }
        @Override public void stop() { stops.incrementAndGet(); }
        @Override public Mono<Msg> dispatch(InboundMessage message) { return Mono.empty(); }
        @Override public void deliver(OutboundAddress address, List<Msg> messages) { delivered.addAll(messages); }
    }

    private static PigAgent agent(Model model, Path ws) {
        return PigAgent.builder().name("chan").sysPrompt("sp").model(model)
                .toolkit(new Toolkit()).workspace(ws).build();
    }

    @Test
    void sendRoutesThroughGatewayAndReturnsReply(@TempDir Path ws) {
        PigAgent pig = agent(new TextModel("pong"), ws);
        GatewayChannelKernel kernel = GatewayChannelKernel.forAgent(pig.getHarnessAgent());

        Msg reply = kernel.send(SendOptions.userId("u1"), "ping").block();

        assertThat(allText(reply)).contains("pong");
        pig.close();
    }

    @Test
    void sendStreamEmitsTypedEvents(@TempDir Path ws) {
        PigAgent pig = agent(new TextModel("streamed-answer"), ws);
        GatewayChannelKernel kernel = GatewayChannelKernel.forAgent(pig.getHarnessAgent());

        List<AgentEvent> events = kernel.sendStream(SendOptions.userId("u1"), "hi").collectList().block();

        assertThat(events).as("the native gateway routed a turn and produced a typed event stream")
                .isNotNull().isNotEmpty();
        pig.close();
    }

    @Test
    void sendOptionsThreadSessionPerUser(@TempDir Path ws) {
        CapturingModel model = new CapturingModel();
        PigAgent pig = agent(model, ws);
        GatewayChannelKernel kernel = GatewayChannelKernel.forAgent(pig.getHarnessAgent());

        // Two turns as the SAME user → the second turn sees the first turn's history (continuation).
        kernel.send(SendOptions.userId("alice"), "first").block();
        kernel.send(SendOptions.userId("alice"), "second").block();
        // A different user → an isolated session (only its own single user message).
        kernel.send(SendOptions.userId("bob"), "hello").block();

        assertThat(model.userMsgCount(0)).as("alice turn 1 has one user message").isEqualTo(1);
        assertThat(model.userMsgCount(1)).as("alice turn 2 continues her session (>1 user message)")
                .isGreaterThan(1);
        assertThat(model.userMsgCount(2)).as("bob's session is isolated from alice's").isEqualTo(1);
        pig.close();
    }

    @Test
    void nativeChannelsStartAndStopWithKernel(@TempDir Path ws) {
        PigAgent pig = agent(new TextModel("ok"), ws);
        StubNativeChannel stub = new StubNativeChannel();
        GatewayChannelKernel kernel = GatewayChannelKernel.builder(pig.getHarnessAgent())
                .nativeChannel(stub).build();

        assertThat(kernel.nativeChannels()).hasSize(1);
        kernel.start();
        assertThat(stub.inits.get()).isEqualTo(1);
        assertThat(stub.starts.get()).isEqualTo(1);
        kernel.start(); // idempotent
        assertThat(stub.starts.get()).isEqualTo(1);
        kernel.stop();
        assertThat(stub.stops.get()).isEqualTo(1);
        pig.close();
    }
}
