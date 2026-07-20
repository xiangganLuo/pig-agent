package io.pigagent.channel;

import java.util.function.Consumer;

/**
 * A messaging channel adapter — one integration point between an external transport (an HTTP webhook,
 * a DingTalk/Feishu robot, Slack, a CLI pipe, …) and the agent.
 *
 * <h2>Lifecycle contract</h2>
 * <ol>
 *   <li><b>start(handler)</b> — wire up inbound delivery. The adapter begins listening on its
 *       transport and, for each inbound message, invokes {@code handler.accept(text)} with the
 *       message text. The supplied handler is {@link ChannelAgentBridge}, which routes the text
 *       through the (channel-mode) agent. {@code start} MUST be idempotent-safe to call once per
 *       adapter instance.</li>
 *   <li><b>inbound → bridge → agent → outbound</b> — the bridge runs the agent and calls
 *       {@link #sendMessage(String)} with the agent's reply. An adapter delivers that reply back
 *       over its transport (e.g. an HTTP response body, a bot API post, stdout). The bridge invokes
 *       the handler synchronously (it blocks on the agent turn), so a request/response transport MAY
 *       correlate the reply with the in-flight inbound request.</li>
 *   <li><b>stop()</b> — release the transport resources (sockets, threads, connections). After
 *       {@code stop}, no further inbound messages are delivered.</li>
 *   <li><b>isRunning()</b> — reflects whether the adapter is currently listening (true after a
 *       successful {@code start}, false after {@code stop}).</li>
 * </ol>
 *
 * <h2>Adding a new adapter</h2>
 * Implement this interface and register the channel id in {@link ChannelFactory} (and, if built at
 * runtime, {@link ChannelRegistry}); no change to {@link ChannelAgentBridge} or existing adapters is
 * required. Per-channel configuration lives under {@code channels.<id>} in {@code application.yaml}
 * ({@code io.pigagent.config.PigAgentConfig.ChannelConfig}); channels default to disabled. Adapters
 * MUST NOT log or echo credentials ({@code token} / signing secrets).
 */
public interface Channel {
    /** Stable, lowercase identifier used as the {@code channels.<id>} config key and registry key. */
    String channelId();

    /** Human-readable name for logs / status output. */
    String displayName();

    /**
     * Begin listening; for each inbound message invoke {@code messageHandler.accept(text)}.
     * The handler is typically {@link ChannelAgentBridge}, invoked synchronously by the adapter.
     */
    void start(Consumer<String> messageHandler);

    /** Deliver an outbound message (an agent reply) back over the transport. */
    void sendMessage(String message);

    /** Stop listening and release transport resources. */
    void stop();

    /** Whether the adapter is currently listening. */
    boolean isRunning();
}
