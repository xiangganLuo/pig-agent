package io.pigagent.channel;

import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.pigagent.core.agent.PigAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges a Channel to a PigAgent.
 * Routes inbound channel messages to the agent and sends responses back.
 */
public final class ChannelAgentBridge {

    private static final Logger log = LoggerFactory.getLogger(ChannelAgentBridge.class);

    private final PigAgent agent;
    private final Channel channel;

    public ChannelAgentBridge(PigAgent agent, Channel channel) {
        this.agent = agent;
        this.channel = channel;
    }

    public void start() {
        channel.start(this::handleMessage);
        log.info("Channel '{}' connected to agent '{}'", channel.channelId(), agent.getAgentName());
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

        Msg userMsg = Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(userInput).build())
                .build();

        try {
            StringBuilder response = new StringBuilder();
            agent.stream(userMsg).doOnNext(event -> {
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
