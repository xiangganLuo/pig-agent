package io.pigagent.channel.http;

/**
 * Pure request handler: maps an {@link InboundHttp} to an {@link OutboundHttp}. Implementations
 * MUST NOT touch the raw socket — {@link HttpChannelServer} owns the transport, so the handler stays
 * unit-testable without a real server.
 */
@FunctionalInterface
public interface RequestHandler {
    OutboundHttp handle(InboundHttp request);
}
