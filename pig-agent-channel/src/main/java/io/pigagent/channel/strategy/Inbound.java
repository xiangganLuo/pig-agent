package io.pigagent.channel.strategy;

import io.pigagent.channel.http.OutboundHttp;

/**
 * The immutable outcome of {@link ChannelStrategy#inbound} — exactly one of two shapes:
 * <ul>
 *   <li>a <b>direct response</b> ({@link #respond}) the channel writes back as-is (a
 *       {@code url_verification} challenge echo, a {@code 401}/{@code 405}, …) without touching the
 *       agent; or</li>
 *   <li>a <b>routable message</b> ({@link #route}) — the extracted user text to run through the
 *       {@code ChannelAgentBridge} → agent (the reply is delivered out-of-band via
 *       {@link ChannelStrategy#send}).</li>
 * </ul>
 * {@link #ignore()} is neither: a well-formed but irrelevant inbound (e.g. a non-message event) that
 * the channel simply acks. Keeping this a pure record lets each strategy's inbound logic be unit-tested
 * without a socket.
 */
public record Inbound(OutboundHttp response, String message) {

    /** A direct HTTP response to write back without routing to the agent. */
    public static Inbound respond(OutboundHttp response) {
        return new Inbound(response, null);
    }

    /** A user message to route to the agent (reply delivered out-of-band via {@link ChannelStrategy#send}). */
    public static Inbound route(String message) {
        return new Inbound(null, message);
    }

    /** A well-formed but irrelevant inbound — neither a direct response nor a routable message. */
    public static Inbound ignore() {
        return new Inbound(null, null);
    }

    /** Whether this carries a direct response to write back. */
    public boolean isDirect() {
        return response != null;
    }

    /** Whether this carries a non-blank user message to route. */
    public boolean hasMessage() {
        return message != null && !message.isBlank();
    }
}
