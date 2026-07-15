package io.pigagent.channel.http;

/**
 * A minimal, immutable HTTP response returned by a {@link RequestHandler}. A null/empty {@code body}
 * yields a bodyless response; {@code contentType} may be null (no {@code Content-Type} header set).
 */
public record OutboundHttp(int status, String contentType, String body) {

    private static final String JSON = "application/json; charset=utf-8";
    private static final String TEXT = "text/plain; charset=utf-8";

    public static OutboundHttp json(int status, String body) {
        return new OutboundHttp(status, JSON, body);
    }

    public static OutboundHttp text(int status, String body) {
        return new OutboundHttp(status, TEXT, body);
    }
}
