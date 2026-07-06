package io.pigagent.channel;

import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.pigagent.core.agent.AgentHolder;
import io.pigagent.core.agent.kernel.AgentKernel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges a Channel to the live agent (read through an {@link AgentHolder} so it follows
 * runtime model switches). Routes inbound channel messages to the agent and sends responses back.
 *
 * <p>The channel runs on its own agent holder / channel-mode permission track (a separate track,
 * not one of the kernel's switchable agents). When an {@link AgentKernel} is supplied it is only
 * used to make channel turns <em>observable</em> through the façade event stream (Web/status),
 * never to route or switch the channel agent — see {@link AgentKernel#noteChannelChat}.
 */
public final class ChannelAgentBridge {

    private static final Logger log = LoggerFactory.getLogger(ChannelAgentBridge.class);

    private final AgentHolder agentHolder;
    private final Channel channel;
    private final AgentKernel kernel; // nullable: only for façade visibility of channel activity

    public ChannelAgentBridge(AgentHolder agentHolder, Channel channel) {
        this(agentHolder, channel, null);
    }

    public ChannelAgentBridge(AgentHolder agentHolder, Channel channel, AgentKernel kernel) {
        this.agentHolder = agentHolder;
        this.channel = channel;
        this.kernel = kernel;
    }

    public void start() {
        channel.start(this::handleMessage);
        log.info("Channel '{}' connected to agent '{}'", channel.channelId(), agentHolder.get().getAgentName());
    }

    public void stop() {
        channel.stop();
    }

    public Channel getChannel() {
        return channel;
    }

    private void handleMessage(String userInput) {
        if (userInput == null || userInput.isBlank()) return;

        log.debug("Received from channel '{}': {}", channel.channelId(), userInput);
        if (kernel != null) {
            kernel.noteChannelChat(channel.channelId()); // façade-visible, separate-track
        }

        Msg userMsg = Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(userInput).build())
                .build();

        try {
            StringBuilder response = new StringBuilder();
            agentHolder.get().stream(userMsg).doOnNext(event -> {
                if (event.getType() == EventType.AGENT_RESULT) {
                    response.append(event.getMessage().getTextContent());
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
        } catch (Exception e) {
            log.error("Failed to process message on channel '{}': {}", channel.channelId(), e.getMessage(), e);
            channel.sendMessage("Error: " + e.getMessage());
        }
    }
}
