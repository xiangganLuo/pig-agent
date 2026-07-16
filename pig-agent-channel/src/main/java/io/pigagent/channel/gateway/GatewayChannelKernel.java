package io.pigagent.channel.gateway;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.gateway.channel.chatui.SendOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * pig's channel <b>kernel built on the native AgentScope 2.0 {@code Gateway}</b> (av2 Gateway
 * enhancement — "在原生内核之上盖房子"). It adopts the native {@link Gateway} (session management +
 * single-session fair queuing + agent routing) and its built-in {@link ChatUiChannel} as the routing
 * engine, while sitting <b>behind</b> pig's own channel seam ({@code ChannelAgentBridge} / the kernel
 * façade) so the frontend contract is unchanged.
 *
 * <h2>How it's wired (javap-grounded, offline-verifiable)</h2>
 * Built over a pig {@link HarnessAgent} (the vehicle {@code PigAgent.getHarnessAgent()} exposes):
 * {@code mainAgent.channel(ChatUiChannel.create())} lazily creates the agent's internal gateway,
 * registers the agent, and injects that gateway into the channel — this is also the documented enabler
 * for {@code expose_to_user} (the subagent→user bridge). Additional <em>peer</em> agents are registered
 * via {@link Gateway#registerAgent}; native platform adapters are attached to the same gateway via
 * {@link Channel#init}/{@link Channel#start} on {@link #start()}.
 *
 * <h2>Session threading</h2>
 * {@link SendOptions} threads {@code (userId, sessionId, agentId)} into every turn — consistent with
 * pig's Phase-3 per-{@code (userId,sessionId)} {@code AgentStateStore}, so each channel user/session
 * gets an isolated, persistent conversation, and single-session concurrency is fairly queued by the
 * native gateway.
 *
 * <h2>Subagent exposure (expose_to_user)</h2>
 * A subagent spawned with {@code expose_to_user=true} makes the native gateway register a user-addressable
 * entry point and emit a {@code SubagentExposedEvent} (carrying a {@code subagentId}) onto the stream; a
 * client then talks to that subagent directly via {@link #sendToSubagent}/{@link #sendToSubagentStream},
 * bypassing the parent. This kernel is exactly the {@code agent.channel(...)}-bound {@code Channel} the
 * native docs require for that bridge to auto-assemble.
 *
 * <p>Not thread-safe to build concurrently; once built it delegates to the (thread-safe) native gateway.
 * The bound {@link HarnessAgent} is fixed at build time (the native gateway binds the agent when the
 * channel is created); a model switch that rebuilds the agent needs a fresh kernel (documented limitation
 * of the opt-in path).
 */
public final class GatewayChannelKernel {

    private static final Logger log = LoggerFactory.getLogger(GatewayChannelKernel.class);

    private final ChatUiChannel chat;
    private final Gateway gateway;
    private final List<Channel> nativeChannels;
    private volatile boolean started;

    private GatewayChannelKernel(ChatUiChannel chat, Gateway gateway, List<Channel> nativeChannels) {
        this.chat = chat;
        this.gateway = gateway;
        this.nativeChannels = nativeChannels;
    }

    public static Builder builder(HarnessAgent mainAgent) {
        return new Builder(mainAgent);
    }

    /** Convenience: a kernel over just the main agent (no peers, no native channels). */
    public static GatewayChannelKernel forAgent(HarnessAgent mainAgent) {
        return builder(mainAgent).build();
    }

    /** Send a message and get the final reply, session-threaded by {@code options}. */
    public Mono<Msg> send(SendOptions options, String message) {
        return chat.send(options, message);
    }

    /** Stream a turn's typed {@link AgentEvent}s, session-threaded by {@code options}. */
    public Flux<AgentEvent> sendStream(SendOptions options, String message) {
        return chat.sendStream(options, message);
    }

    /**
     * Talk directly to a subagent previously exposed via {@code expose_to_user=true} (bypassing the
     * parent), addressed by the {@code subagentId} carried on the {@code SubagentExposedEvent}.
     */
    public Mono<Msg> sendToSubagent(String subagentId, String message) {
        return chat.sendToSubagent(subagentId, message);
    }

    /** Streaming counterpart of {@link #sendToSubagent}. */
    public Flux<AgentEvent> sendToSubagentStream(String subagentId, String message) {
        return chat.sendToSubagentStream(subagentId, message);
    }

    /** The native {@link ChatUiChannel} this kernel drives (for advanced routing / outbound polling). */
    public ChatUiChannel chatUiChannel() {
        return chat;
    }

    /** The native {@link Gateway} the main agent + peers + native channels are wired to. */
    public Gateway gateway() {
        return gateway;
    }

    /** The native platform adapters attached to this gateway (empty when none). */
    public List<Channel> nativeChannels() {
        return List.copyOf(nativeChannels);
    }

    /** Start the attached native channels (idempotent). The {@link ChatUiChannel} needs no start. */
    public void start() {
        if (started) {
            return;
        }
        started = true;
        for (Channel channel : nativeChannels) {
            try {
                channel.init(gateway);
                channel.start();
                log.info("Native channel '{}' started on the gateway", channel.channelId());
            } catch (RuntimeException e) {
                log.warn("Native channel '{}' failed to start ({})",
                        channel.channelId(), e.getClass().getSimpleName());
            }
        }
    }

    /** Stop the attached native channels (idempotent). */
    public void stop() {
        if (!started) {
            return;
        }
        started = false;
        for (Channel channel : nativeChannels) {
            try {
                channel.stop();
            } catch (RuntimeException e) {
                log.debug("Native channel '{}' stop failed (ignored): {}",
                        channel.channelId(), e.getMessage());
            }
        }
    }

    public boolean isStarted() {
        return started;
    }

    /** Assembles a {@link GatewayChannelKernel}: main agent (required) + optional peers + native channels. */
    public static final class Builder {
        private final HarnessAgent mainAgent;
        private final Map<String, HarnessAgent> peers = new LinkedHashMap<>();
        private final List<Channel> nativeChannels = new ArrayList<>();

        private Builder(HarnessAgent mainAgent) {
            this.mainAgent = Objects.requireNonNull(mainAgent, "mainAgent");
        }

        /** Register a peer agent addressable via {@code SendOptions.withAgentId(id)}. */
        public Builder peer(String id, HarnessAgent agent) {
            if (id != null && !id.isBlank() && agent != null) {
                peers.put(id, agent);
            }
            return this;
        }

        /** Attach a native platform adapter (started/stopped with the kernel). */
        public Builder nativeChannel(Channel channel) {
            if (channel != null) {
                nativeChannels.add(channel);
            }
            return this;
        }

        public GatewayChannelKernel build() {
            // agent.channel(create()) lazily creates the agent's internal gateway, registers the agent
            // as main, injects the gateway into the ChatUiChannel, and wires the subagent-gateway bridge
            // (the enabler for expose_to_user). We then reuse that same gateway for peers + native channels.
            ChatUiChannel chat = mainAgent.channel(ChatUiChannel.create());
            Gateway gateway = mainAgent.gateway();
            peers.forEach(gateway::registerAgent);
            return new GatewayChannelKernel(chat, gateway, List.copyOf(nativeChannels));
        }
    }
}
