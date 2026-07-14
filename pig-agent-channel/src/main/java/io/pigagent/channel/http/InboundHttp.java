package io.pigagent.channel.http;

import java.util.Map;

/**
 * A minimal, immutable view of an inbound HTTP request handed to a {@link RequestHandler}.
 * Header keys are normalized to lowercase by {@link HttpChannelServer} so lookups are case-insensitive.
 */
public record InboundHttp(String method, Map<String, String> headers, String body) {

    /** Case-insensitive header lookup (keys are already lowercased); null if absent. */
    public String header(String name) {
        return headers == null ? null : headers.get(name.toLowerCase(java.util.Locale.ROOT));
    }
}
