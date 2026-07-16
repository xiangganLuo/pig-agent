package io.pigagent.channel;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.harness.agent.gateway.channel.chatui.SendOptions;
import io.pigagent.channel.gateway.GatewayChannelKernel;
import io.pigagent.core.agent.AgentHolder;
import io.pigagent.core.agent.kernel.AgentKernel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Bridges a Channel to the live agent (read through an {@link AgentHolder} so it follows
 * runtime model switches). Routes inbound channel messages to the agent and sends responses back.
 *
 * <p>The channel runs on its own agent holder / channel-mode permission track (a separate track,
 * not one of the kernel's switchable agents). When an {@link AgentKernel} is supplied it is only
 * used to make channel turns <em>observable</em> through the façade event stream (Web/status),
 * never to route or switch the channel agent — see {@link AgentKernel#noteChannelChat}.
 *
 * <h2>Routing paths (av2 Gateway enhancement)</h2>
 * There are two, and the default is unchanged:
 * <ul>
 *   <li><b>Direct (default):</b> when no {@link GatewayChannelKernel} is supplied, the inbound text
 *       streams straight through {@code agentHolder.get().stream(msg, "channel:<id>")} — exactly the
 *       prior behavior (backward compatible).</li>
 *   <li><b>Native gateway (opt-in):</b> when a {@link GatewayChannelKernel} is supplied (config
 *       {@code channel-gateway.enabled}), the turn is routed through the native
 *       {@code Gateway}/{@code ChatUiChannel} — session management, single-session fair queuing and
 *       agent routing — via {@link GatewayChannelKernel#sendStream} threaded by
 *       {@link SendOptions#of(String, String)} on {@code (userId="pig", "channel:<id>")}. The frontend
 *       seam (this bridge) is unchanged.</li>
 * </ul>
 * Either way the reply text is aggregated from typed {@link TextBlockDeltaEvent}s and delivered via
 * {@link Channel#sendMessage}.
 */
public final class ChannelAgentBridge {

    private static final Logger log = LoggerFactory.getLogger(ChannelAgentBridge.class);

    /** State-store partition for this single-user terminal app (matches {@code PigAgent.USER_ID}). */
    private static final String USER_ID = "pig";

    private final AgentHolder agentHolder;
    private final Channel channel;
    private final AgentKernel kernel; // nullable: only for façade visibility of channel activity
    private final GatewayChannelKernel gatewayKernel; // nullable: opt-in native gateway routing

    public ChannelAgentBridge(AgentHolder agentHolder, Channel channel) {
        this(agentHolder, channel, null, null);
    }

    public ChannelAgentBridge(AgentHolder agentHolder, Channel channel, AgentKernel kernel) {
        this(agentHolder, channel, kernel, null);
    }

    public ChannelAgentBridge(AgentHolder agentHolder, Channel channel, AgentKernel kernel,
                              GatewayChannelKernel gatewayKernel) {
        this.agentHolder = agentHolder;
        this.channel = channel;
        this.kernel = kernel;
        this.gatewayKernel = gatewayKernel;
    }

    public void start() {
        channel.start(this::handleMessage);
        log.info("Channel '{}' connected to agent '{}'{}", channel.channelId(),
                agentHolder.get().getAgentName(), gatewayKernel != null ? " (native gateway)" : "");
    }

    public void stop() {
        channel.stop();
    }

    public Channel getChannel() {
        return channel;
    }

    /** The stable per-channel conversation session id (its own native state slot). */
    private String channelSessionId() {
        return "channel:" + channel.channelId();
    }

    private void handleMessage(String userInput) {
        if (userInput == null || userInput.isBlank()) return;

        log.debug("Received from channel '{}': {}", channel.channelId(), userInput);
        if (kernel != null) {
            kernel.noteChannelChat(channel.channelId()); // façade-visible, separate-track
        }

        try {
            runStream(streamFor(userInput));
        } catch (Exception e) {
            log.error("Failed to process message on channel '{}': {}", channel.channelId(), e.getMessage(), e);
            channel.sendMessage("Error: " + e.getMessage());
        }
    }

    /**
     * The event stream for this turn: the native gateway path when a {@link GatewayChannelKernel} is
     * wired (opt-in), else the direct per-session agent stream (default). Both are bound to the
     * channel-owned session id so the conversation persists in its own {@code (pig, "channel:<id>")} slot.
     */
    private Flux<AgentEvent> streamFor(String userInput) {
        if (gatewayKernel != null) {
            return gatewayKernel.sendStream(SendOptions.of(USER_ID, channelSessionId()), userInput);
        }
        Msg userMsg = Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(userInput).build())
                .build();
        return agentHolder.get().stream(userMsg, channelSessionId());
    }

    /** Aggregate answer deltas and deliver the reply (shared by both routing paths). */
    private void runStream(Flux<AgentEvent> stream) {
        StringBuilder response = new StringBuilder();
        stream.doOnNext(event -> {
            if (event instanceof TextBlockDeltaEvent delta) {
                response.append(delta.getDelta());
            }
        }).doOnComplete(() -> {
            String text = response.toString();
            if (!text.isEmpty()) {
                channel.sendMessage(text);
                log.debug("Sent to channel '{}': {}", channel.channelId(),
                        text.length() > 100 ? text.substring(0, 100) + "..." : text);
            }
        }).doOnError(e -> {
            log.error("Agent error on channel '{}': {}", channel.channelId(), e.getMessage(), e);
            channel.sendMessage("Error: " + e.getMessage());
        }).blockLast();
    }
}
